package org.dromara.gz.bean.service.impl;

import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.tenant.helper.TenantHelper;
import org.dromara.gz.bean.domain.entity.GzBeanStore;
import org.dromara.gz.bean.domain.vo.GzBeanRevenueVO;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

/**
 * {@link GzBeanRevenueServiceImpl} 单测（拼豆营业额，客户 0702 反馈 #5）。
 *
 * <p>覆盖：</p>
 * <ul>
 *   <li>happy path：汇总透传 mapper 结果 + 门店名 enrich + 桌型空快照兜底「未知桌型」</li>
 *   <li>staff 门店隔离：staffStoreId 非空时覆盖前端传入 storeId（防越权）</li>
 *   <li>owner 全部门店：storeId=null → mapper storeId 传 null + 门店名「全部门店」</li>
 *   <li>日期格式非法 → ServiceException</li>
 *   <li>summary 为 null（防御）→ 全 0 不抛</li>
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

    private GzBeanRevenueVO.Summary summary(long total, long cnt, long cash, long cashCnt, long online, long onlineCnt) {
        GzBeanRevenueVO.Summary s = new GzBeanRevenueVO.Summary();
        s.setTotalCent(total);
        s.setOrderCount(cnt);
        s.setCashCent(cash);
        s.setCashCount(cashCnt);
        s.setOnlineCent(online);
        s.setOnlineCount(onlineCnt);
        return s;
    }

    private GzBeanRevenueVO.TypeGroup typeGroup(String name, long total, long cnt) {
        GzBeanRevenueVO.TypeGroup g = new GzBeanRevenueVO.TypeGroup();
        g.setTypeName(name);
        g.setTotalCent(total);
        g.setOrderCount(cnt);
        return g;
    }

    @Test
    @DisplayName("happy path：汇总透传 + 门店名 enrich + 空桌型快照兜底")
    void selectDailyRevenue_happyPath() {
        try (MockedStatic<TenantHelper> th = mockStatic(TenantHelper.class)) {
            th.when(TenantHelper::getTenantId).thenReturn("1001");

            when(bookingMapper.sumDailyRevenue("1001", 1L, LocalDate.of(2026, 7, 3)))
                .thenReturn(summary(50000, 8, 20000, 3, 30000, 5));
            when(bookingMapper.sumDailyRevenueByType("1001", 1L, LocalDate.of(2026, 7, 3)))
                .thenReturn(List.of(
                    typeGroup("双人桌", 30000, 5),
                    typeGroup(null, 20000, 3)   // 空快照 → 应兜底「未知桌型」
                ));
            GzBeanStore store = new GzBeanStore();
            store.setId(1L);
            store.setName("成都太古里店");
            when(storeMapper.selectById(1L)).thenReturn(store);

            GzBeanRevenueVO vo = service.selectDailyRevenue(1L, "2026-07-03", null);

            assertEquals(50000L, vo.getTotalCent());
            assertEquals(8L, vo.getOrderCount());
            assertEquals(20000L, vo.getCashCent());
            assertEquals(3L, vo.getCashCount());
            assertEquals(30000L, vo.getOnlineCent());
            assertEquals(5L, vo.getOnlineCount());
            assertEquals("成都太古里店", vo.getStoreName());
            assertEquals("2026-07-03", vo.getDate());
            assertEquals(2, vo.getByType().size());
            assertEquals("双人桌", vo.getByType().get(0).getTypeName());
            assertEquals("未知桌型", vo.getByType().get(1).getTypeName());
        }
    }

    @Test
    @DisplayName("staff 门店隔离：staffStoreId 覆盖前端传入 storeId")
    void selectDailyRevenue_staffScopeOverridesStoreId() {
        try (MockedStatic<TenantHelper> th = mockStatic(TenantHelper.class)) {
            th.when(TenantHelper::getTenantId).thenReturn("1001");
            when(bookingMapper.sumDailyRevenue(eq("1001"), eq(2L), any())).thenReturn(summary(0, 0, 0, 0, 0, 0));
            when(bookingMapper.sumDailyRevenueByType(eq("1001"), eq(2L), any())).thenReturn(List.of());
            GzBeanStore store = new GzBeanStore();
            store.setId(2L);
            store.setName("本店");
            when(storeMapper.selectById(2L)).thenReturn(store);

            // 前端恶意传 storeId=99（想看别店），staff 绑定门店=2 → 强制按 2
            service.selectDailyRevenue(99L, "2026-07-03", 2L);

            ArgumentCaptor<Long> storeCap = ArgumentCaptor.forClass(Long.class);
            verify(bookingMapper).sumDailyRevenue(eq("1001"), storeCap.capture(), any());
            assertEquals(2L, storeCap.getValue(), "staff 应被强制按绑定门店，忽略前端 storeId");
        }
    }

    @Test
    @DisplayName("owner 全部门店：storeId=null → mapper 传 null + 门店名『全部门店』")
    void selectDailyRevenue_ownerAllStores() {
        try (MockedStatic<TenantHelper> th = mockStatic(TenantHelper.class)) {
            th.when(TenantHelper::getTenantId).thenReturn("1001");
            when(bookingMapper.sumDailyRevenue(eq("1001"), isNull(), any())).thenReturn(summary(12345, 2, 12345, 2, 0, 0));
            when(bookingMapper.sumDailyRevenueByType(eq("1001"), isNull(), any())).thenReturn(List.of());

            GzBeanRevenueVO vo = service.selectDailyRevenue(null, "2026-07-03", null);

            assertEquals("全部门店", vo.getStoreName());
            assertEquals(12345L, vo.getTotalCent());
            verify(bookingMapper).sumDailyRevenue(eq("1001"), isNull(), any());
            verify(storeMapper, never()).selectById(any());
        }
    }

    @Test
    @DisplayName("日期格式非法 → ServiceException")
    void selectDailyRevenue_badDate() {
        try (MockedStatic<TenantHelper> th = mockStatic(TenantHelper.class)) {
            th.when(TenantHelper::getTenantId).thenReturn("1001");
            assertThrows(ServiceException.class, () -> service.selectDailyRevenue(1L, "2026/07/03", null));
        }
    }

    @Test
    @DisplayName("summary 为 null（无数据防御）→ 全 0 不抛")
    void selectDailyRevenue_nullSummaryDefensive() {
        try (MockedStatic<TenantHelper> th = mockStatic(TenantHelper.class)) {
            th.when(TenantHelper::getTenantId).thenReturn("1001");
            when(bookingMapper.sumDailyRevenue(eq("1001"), eq(1L), any())).thenReturn(null);
            when(bookingMapper.sumDailyRevenueByType(eq("1001"), eq(1L), any())).thenReturn(List.of());
            GzBeanStore store = new GzBeanStore();
            store.setId(1L);
            store.setName("门店A");
            when(storeMapper.selectById(1L)).thenReturn(store);

            GzBeanRevenueVO vo = service.selectDailyRevenue(1L, "2026-07-03", null);
            assertEquals(0L, vo.getTotalCent());
            assertEquals(0L, vo.getOrderCount());
            assertEquals(0L, vo.getCashCent());
            assertEquals(0L, vo.getOnlineCent());
            assertTrue(vo.getByType().isEmpty());
        }
    }

    @Test
    @DisplayName("未登录（tenant 空）→ ServiceException")
    void selectDailyRevenue_noTenant() {
        try (MockedStatic<TenantHelper> th = mockStatic(TenantHelper.class)) {
            th.when(TenantHelper::getTenantId).thenReturn("");
            assertThrows(ServiceException.class, () -> service.selectDailyRevenue(1L, "2026-07-03", null));
        }
    }
}
