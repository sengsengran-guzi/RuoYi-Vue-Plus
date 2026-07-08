package org.dromara.gz.bean.service.impl;

import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.tenant.helper.TenantHelper;
import org.dromara.gz.bean.domain.bo.GzBeanRevenueQueryBo;
import org.dromara.gz.bean.domain.entity.GzBeanStore;
import org.dromara.gz.bean.domain.vo.GzBeanRevenueAggregateVO;
import org.dromara.gz.bean.mapper.GzBeanBookingMapper;
import org.dromara.gz.bean.mapper.GzBeanStoreMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

/**
 * {@link GzBeanRevenueServiceImpl} 单测（拼豆营业额区间聚合）。
 *
 * <p>覆盖：</p>
 * <ul>
 *   <li>happy path：透视成 categories / periods（零填充成矩形）/ byCategory，三处金额同源</li>
 *   <li>类目排序：单/双/四/自定义/unknown 在前后，计时(0) 先于包天(1)</li>
 *   <li>自定义桌型 typeName 快照兜底</li>
 *   <li>staff 门店隔离：staffStoreId 覆盖前端 storeId（防越权）</li>
 *   <li>owner 全部门店：storeId=null → mapper 传 null + 门店名「全部门店」</li>
 *   <li>校验：粒度非法 / 日期非法 / start&gt;end / 区间过大 → ServiceException</li>
 *   <li>summary 为 null（无数据防御）→ 全 0 不抛</li>
 *   <li>未登录（tenant 空）→ ServiceException</li>
 *   <li>明细区间校验：既无 date 也无区间 / start&gt;end → ServiceException</li>
 * </ul>
 *
 * @author kevin-coder (sensenran-guzi · 拼豆营业额)
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class GzBeanRevenueServiceImplTest {

    @Mock
    private GzBeanBookingMapper bookingMapper;
    @Mock
    private GzBeanStoreMapper storeMapper;

    @InjectMocks
    private GzBeanRevenueServiceImpl service;

    private GzBeanRevenueAggregateVO.Summary summary(long total, long cnt, long cash, long cashCnt, long online, long onlineCnt) {
        GzBeanRevenueAggregateVO.Summary s = new GzBeanRevenueAggregateVO.Summary();
        s.setTotalCent(total);
        s.setOrderCount(cnt);
        s.setCashCent(cash);
        s.setCashCount(cashCnt);
        s.setOnlineCent(online);
        s.setOnlineCount(onlineCnt);
        return s;
    }

    private GzBeanRevenueAggregateVO.PeriodCategoryRow row(String periodKey, String seatType, int isDayPass,
                                                           long total, long cnt, String typeName) {
        GzBeanRevenueAggregateVO.PeriodCategoryRow r = new GzBeanRevenueAggregateVO.PeriodCategoryRow();
        r.setPeriodKey(periodKey);
        r.setSeatType(seatType);
        r.setIsDayPass(isDayPass);
        r.setTotalCent(total);
        r.setOrderCount(cnt);
        r.setTypeName(typeName);
        return r;
    }

    @Test
    @DisplayName("happy path：透视 categories/periods(零填充矩形)/byCategory + 三处金额同源")
    void selectAggregate_happyPathPivotAndConsistency() {
        try (MockedStatic<TenantHelper> th = mockStatic(TenantHelper.class)) {
            th.when(TenantHelper::getTenantId).thenReturn("1001");

            when(bookingMapper.sumRangeRevenue(eq("1001"), eq(1L), any(), any()))
                .thenReturn(summary(35000, 4, 15000, 3, 20000, 1));
            // 2026-06 有 single|0 + double|1；2026-07 只有 single|0（double|1 缺 → 应零填充）
            when(bookingMapper.sumRangeByPeriodCategory(eq("1001"), eq(1L), any(), any(), eq("month")))
                .thenReturn(List.of(
                    row("2026-06", "single", 0, 10000, 2, "单人桌"),
                    row("2026-06", "double", 1, 20000, 1, "双人桌"),
                    row("2026-07", "single", 0, 5000, 1, "单人桌")
                ));
            GzBeanStore store = new GzBeanStore();
            store.setId(1L);
            store.setName("成都太古里店");
            when(storeMapper.selectById(1L)).thenReturn(store);

            GzBeanRevenueAggregateVO vo = service.selectAggregate("month", "2026-06-01", "2026-07-31", 1L, null);

            // 门店 + 回显
            assertEquals("成都太古里店", vo.getStoreName());
            assertEquals("month", vo.getGranularity());
            assertEquals("2026-06-01", vo.getStartDate());
            assertEquals("2026-07-31", vo.getEndDate());

            // 类目：single|0 在 double|1 前
            assertEquals(2, vo.getCategories().size());
            assertEquals("single|0", vo.getCategories().get(0).getKey());
            assertEquals("double|1", vo.getCategories().get(1).getKey());

            // byCategory：single|0=15000/3, double|1=20000/1
            var single0 = vo.getByCategory().stream().filter(c -> "single|0".equals(c.getKey())).findFirst().orElseThrow();
            var double1 = vo.getByCategory().stream().filter(c -> "double|1".equals(c.getKey())).findFirst().orElseThrow();
            assertEquals(15000L, single0.getTotalCent());
            assertEquals(3L, single0.getOrderCount());
            assertEquals(20000L, double1.getTotalCent());
            assertEquals(1L, double1.getOrderCount());

            // periods：2 桶，按 key 升序；每桶 cells 矩形（长度 == categories）
            assertEquals(2, vo.getPeriods().size());
            assertEquals("2026-06", vo.getPeriods().get(0).getKey());
            assertEquals("2026-07", vo.getPeriods().get(1).getKey());
            assertEquals(2, vo.getPeriods().get(0).getCells().size());
            assertEquals(2, vo.getPeriods().get(1).getCells().size());
            // 2026-06 总额 30000；2026-07 总额 5000
            assertEquals(30000L, vo.getPeriods().get(0).getTotalCent());
            assertEquals(5000L, vo.getPeriods().get(1).getTotalCent());
            // 零填充：2026-07 的 double|1 cell 金额 0
            var jul = vo.getPeriods().get(1);
            var julDouble1 = jul.getCells().stream().filter(c -> "double|1".equals(c.getCatKey())).findFirst().orElseThrow();
            assertEquals(0L, julDouble1.getAmountCent());
            assertEquals(0L, julDouble1.getOrderCount());

            // 三处同源：summary.total == Σ byCategory == Σ periods
            long byCatSum = vo.getByCategory().stream().mapToLong(GzBeanRevenueAggregateVO.CategoryTotal::getTotalCent).sum();
            long periodSum = vo.getPeriods().stream().mapToLong(GzBeanRevenueAggregateVO.PeriodBucket::getTotalCent).sum();
            assertEquals(35000L, vo.getSummary().getTotalCent());
            assertEquals(35000L, byCatSum);
            assertEquals(35000L, periodSum);
        }
    }

    @Test
    @DisplayName("类目排序：单/双/四/自定义/unknown 在前后，计时先于包天")
    void selectAggregate_categoryOrdering() {
        try (MockedStatic<TenantHelper> th = mockStatic(TenantHelper.class)) {
            th.when(TenantHelper::getTenantId).thenReturn("1001");
            when(bookingMapper.sumRangeRevenue(any(), any(), any(), any())).thenReturn(summary(600, 6, 600, 6, 0, 0));
            // 打乱顺序喂入
            when(bookingMapper.sumRangeByPeriodCategory(any(), any(), any(), any(), any()))
                .thenReturn(List.of(
                    row("2026-06", "quad", 1, 100, 1, "四人桌"),
                    row("2026-06", "single", 1, 100, 1, "单人桌"),
                    row("2026-06", "single", 0, 100, 1, "单人桌"),
                    row("2026-06", "st42", 0, 100, 1, "六人桌"),
                    row("2026-06", "unknown", 0, 100, 1, null),
                    row("2026-06", "double", 0, 100, 1, "双人桌")
                ));
            when(storeMapper.selectById(1L)).thenReturn(null);

            GzBeanRevenueAggregateVO vo = service.selectAggregate("month", "2026-06-01", "2026-06-30", 1L, null);

            List<String> keys = new ArrayList<>();
            vo.getCategories().forEach(c -> keys.add(c.getKey()));
            assertEquals(List.of("single|0", "single|1", "double|0", "quad|1", "st42|0", "unknown|0"), keys);

            // 自定义桌型 typeName 快照兜底保留
            var st42 = vo.getCategories().stream().filter(c -> "st42|0".equals(c.getKey())).findFirst().orElseThrow();
            assertEquals("六人桌", st42.getTypeName());
        }
    }

    @Test
    @DisplayName("staff 门店隔离：staffStoreId 覆盖前端 storeId")
    void selectAggregate_staffScopeOverridesStoreId() {
        try (MockedStatic<TenantHelper> th = mockStatic(TenantHelper.class)) {
            th.when(TenantHelper::getTenantId).thenReturn("1001");
            when(bookingMapper.sumRangeRevenue(eq("1001"), eq(2L), any(), any())).thenReturn(summary(0, 0, 0, 0, 0, 0));
            when(bookingMapper.sumRangeByPeriodCategory(eq("1001"), eq(2L), any(), any(), any())).thenReturn(List.of());
            GzBeanStore store = new GzBeanStore();
            store.setId(2L);
            store.setName("本店");
            when(storeMapper.selectById(2L)).thenReturn(store);

            // 前端恶意传 storeId=99（想看别店），staff 绑定门店=2 → 强制按 2
            service.selectAggregate("month", "2026-06-01", "2026-06-30", 99L, 2L);

            ArgumentCaptor<Long> storeCap = ArgumentCaptor.forClass(Long.class);
            verify(bookingMapper).sumRangeRevenue(eq("1001"), storeCap.capture(), any(), any());
            assertEquals(2L, storeCap.getValue(), "staff 应被强制按绑定门店，忽略前端 storeId");
        }
    }

    @Test
    @DisplayName("owner 全部门店：storeId=null → mapper 传 null + 门店名『全部门店』")
    void selectAggregate_ownerAllStores() {
        try (MockedStatic<TenantHelper> th = mockStatic(TenantHelper.class)) {
            th.when(TenantHelper::getTenantId).thenReturn("1001");
            when(bookingMapper.sumRangeRevenue(eq("1001"), isNull(), any(), any())).thenReturn(summary(12345, 2, 12345, 2, 0, 0));
            when(bookingMapper.sumRangeByPeriodCategory(eq("1001"), isNull(), any(), any(), any())).thenReturn(List.of());

            GzBeanRevenueAggregateVO vo = service.selectAggregate("month", "2026-06-01", "2026-06-30", null, null);

            assertEquals("全部门店", vo.getStoreName());
            assertEquals(12345L, vo.getSummary().getTotalCent());
            verify(bookingMapper).sumRangeRevenue(eq("1001"), isNull(), any(), any());
            verify(storeMapper, never()).selectById(any());
        }
    }

    @Test
    @DisplayName("粒度非法 → ServiceException")
    void selectAggregate_badGranularity() {
        try (MockedStatic<TenantHelper> th = mockStatic(TenantHelper.class)) {
            th.when(TenantHelper::getTenantId).thenReturn("1001");
            assertThrows(ServiceException.class,
                () -> service.selectAggregate("year", "2026-06-01", "2026-06-30", 1L, null));
        }
    }

    @Test
    @DisplayName("日期格式非法 → ServiceException")
    void selectAggregate_badDate() {
        try (MockedStatic<TenantHelper> th = mockStatic(TenantHelper.class)) {
            th.when(TenantHelper::getTenantId).thenReturn("1001");
            assertThrows(ServiceException.class,
                () -> service.selectAggregate("month", "2026/06/01", "2026-06-30", 1L, null));
        }
    }

    @Test
    @DisplayName("start > end → ServiceException")
    void selectAggregate_startAfterEnd() {
        try (MockedStatic<TenantHelper> th = mockStatic(TenantHelper.class)) {
            th.when(TenantHelper::getTenantId).thenReturn("1001");
            assertThrows(ServiceException.class,
                () -> service.selectAggregate("month", "2026-07-10", "2026-06-01", 1L, null));
        }
    }

    @Test
    @DisplayName("区间过大（>400 天）→ ServiceException")
    void selectAggregate_rangeTooLarge() {
        try (MockedStatic<TenantHelper> th = mockStatic(TenantHelper.class)) {
            th.when(TenantHelper::getTenantId).thenReturn("1001");
            assertThrows(ServiceException.class,
                () -> service.selectAggregate("month", "2025-01-01", "2026-12-31", 1L, null));
        }
    }

    @Test
    @DisplayName("summary 为 null（无数据防御）→ 全 0 不抛，类目/桶为空")
    void selectAggregate_nullSummaryDefensive() {
        try (MockedStatic<TenantHelper> th = mockStatic(TenantHelper.class)) {
            th.when(TenantHelper::getTenantId).thenReturn("1001");
            when(bookingMapper.sumRangeRevenue(eq("1001"), eq(1L), any(), any())).thenReturn(null);
            when(bookingMapper.sumRangeByPeriodCategory(eq("1001"), eq(1L), any(), any(), any())).thenReturn(List.of());
            GzBeanStore store = new GzBeanStore();
            store.setId(1L);
            store.setName("门店A");
            when(storeMapper.selectById(1L)).thenReturn(store);

            GzBeanRevenueAggregateVO vo = service.selectAggregate("month", "2026-06-01", "2026-06-30", 1L, null);
            assertEquals(0L, vo.getSummary().getTotalCent());
            assertEquals(0L, vo.getSummary().getOrderCount());
            assertEquals(0L, vo.getSummary().getCashCent());
            assertEquals(0L, vo.getSummary().getOnlineCent());
            assertTrue(vo.getCategories().isEmpty());
            assertTrue(vo.getPeriods().isEmpty());
            assertTrue(vo.getByCategory().isEmpty());
        }
    }

    @Test
    @DisplayName("未登录（tenant 空）→ ServiceException")
    void selectAggregate_noTenant() {
        try (MockedStatic<TenantHelper> th = mockStatic(TenantHelper.class)) {
            th.when(TenantHelper::getTenantId).thenReturn("");
            assertThrows(ServiceException.class,
                () -> service.selectAggregate("month", "2026-06-01", "2026-06-30", 1L, null));
        }
    }

    @Test
    @DisplayName("明细：既无 date 也无区间 → ServiceException")
    void selectDailyDetail_noDateNoRange() {
        GzBeanRevenueQueryBo query = new GzBeanRevenueQueryBo(); // date/startDate/endDate 全空
        assertThrows(ServiceException.class,
            () -> service.selectDailyDetail(query, new PageQuery(1, 10), null));
    }

    @Test
    @DisplayName("明细：区间 start > end → ServiceException")
    void selectDailyDetail_rangeStartAfterEnd() {
        GzBeanRevenueQueryBo query = new GzBeanRevenueQueryBo();
        query.setStartDate(LocalDate.of(2026, 7, 10));
        query.setEndDate(LocalDate.of(2026, 7, 1));
        assertThrows(ServiceException.class,
            () -> service.selectDailyDetail(query, new PageQuery(1, 10), null));
    }
}
