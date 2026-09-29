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
    // GZ-BEAN-059 报表新增依赖
    @Mock
    private org.dromara.gz.bean.mapper.GzBeanSeatTypeConfigMapper seatTypeConfigMapper;
    @Mock
    private org.dromara.gz.bean.mapper.GzBeanSlotQuotaCloseMapper slotQuotaCloseMapper;
    @Mock
    private org.dromara.gz.bean.service.internal.GzBeanHourSlotResolver hourSlotResolver;

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

    // ============================================================
    //  GZ-BEAN-059「桌型使用时长 · 上桌率」月度报表
    // ============================================================

    private static final LocalDate D1 = LocalDate.of(2026, 9, 1);
    private static final LocalDate D2 = LocalDate.of(2026, 9, 2);

    /** 门店 + 单桌型（whole，quantity=4 → 每格容量 4）+ 两个营业窗口 10-12 / 14-16 → 每天 4 格 */
    private void stubUsage(String storeId) {
        GzBeanStore store = new GzBeanStore();
        store.setId(1L);
        store.setName("成都春熙路店");
        store.setTenantId("1001");
        when(storeMapper.selectList(any())).thenReturn(List.of(store));
        when(seatTypeConfigMapper.selectList(any())).thenReturn(List.of(
            org.dromara.gz.bean.domain.entity.GzBeanSeatTypeConfig.builder()
                .id(10L).storeId(1L).seatType("quad").name("四人桌")
                .bookMode("whole").quantity(4).capacity(1).mpVisible(1).build()));
        // 每天 10/11/14/15 四格（午休 12-14 不生成）
        when(hourSlotResolver.selectEnabledSlotsForDate(any(), any(), any())).thenReturn(List.of());
        when(hourSlotResolver.sliceWindowsToHourSlots(any())).thenReturn(List.of(
            java.time.LocalTime.of(10, 0), java.time.LocalTime.of(11, 0),
            java.time.LocalTime.of(14, 0), java.time.LocalTime.of(15, 0)));
        when(bookingMapper.selectUsageRowsInRange(any(), any(), any(), any())).thenReturn(List.of());
        when(slotQuotaCloseMapper.selectCloseRowsInRange(any(), any(), any(), any())).thenReturn(List.of());
    }

    private GzBeanBookingMapper.UsageRow usage(LocalDate d, String status, String payStatus,
                                               java.time.LocalTime s, java.time.LocalTime e, Integer dayPass) {
        GzBeanBookingMapper.UsageRow r = new GzBeanBookingMapper.UsageRow();
        r.setSessDate(d);
        r.setStoreId(1L);
        r.setSeatTypeConfigId(10L);
        r.setSlotStart(s);
        r.setSlotEnd(e);
        r.setStatus(status);
        r.setPayStatus(payStatus);
        r.setIsDayPass(dayPass);
        return r;
    }

    @Test
    @DisplayName("★GZ-BEAN-059 · 时长按「营业格数」而不是钟表时长：包天单 10:00→22:00 只算 4 格（午休不算坐）")
    void seatUsage_countsBusinessSlotsNotClockHours() {
        stubUsage("1001");
        try (MockedStatic<TenantHelper> th = mockStatic(TenantHelper.class)) {
            th.when(TenantHelper::getTenantId).thenReturn("1001");
            when(bookingMapper.selectUsageRowsInRange(any(), any(), any(), any())).thenReturn(List.of(
                // 包天单：钟表 12 小时，但当天只有 4 个营业格 → 时长必须 = 4
                usage(D1, "used", "paid", java.time.LocalTime.of(10, 0), java.time.LocalTime.of(22, 0), 1)));

            List<org.dromara.gz.bean.domain.vo.GzBeanSeatUsageVO> rows =
                service.selectSeatUsage("2026-09-01", "2026-09-01", null, null);

            assertEquals(1, rows.size());
            org.dromara.gz.bean.domain.vo.GzBeanSeatUsageVO vo = rows.get(0);
            assertEquals(4L, vo.getUsedHours(), "包天必须按营业格算（4），不是钟表 12 小时");
            assertEquals(1L, vo.getDayPassBookings());
            assertEquals(1L, vo.getSeatedBookings());
            // 分母 B = 4 格 × 容量 4 = 16；分母 A 无关闭 = 16
            assertEquals(16L, vo.getOpenHours());
            assertEquals(16L, vo.getSellableHours());
        }
    }

    @Test
    @DisplayName("★GZ-BEAN-059 · 未到店 / 取消 / 未支付都不算「坐了」，但各给一列计数")
    void seatUsage_excludesNoShowAndCancelled() {
        stubUsage("1001");
        try (MockedStatic<TenantHelper> th = mockStatic(TenantHelper.class)) {
            th.when(TenantHelper::getTenantId).thenReturn("1001");
            when(bookingMapper.selectUsageRowsInRange(any(), any(), any(), any())).thenReturn(List.of(
                usage(D1, "used", "paid", java.time.LocalTime.of(10, 0), java.time.LocalTime.of(11, 0), 0),
                usage(D1, "no_show", "paid", java.time.LocalTime.of(11, 0), java.time.LocalTime.of(12, 0), 0),
                usage(D1, "cancelled", "pay_closed", java.time.LocalTime.of(14, 0), java.time.LocalTime.of(15, 0), 0),
                usage(D1, "pending", "paying", java.time.LocalTime.of(15, 0), java.time.LocalTime.of(16, 0), 0)));

            org.dromara.gz.bean.domain.vo.GzBeanSeatUsageVO vo =
                service.selectSeatUsage("2026-09-01", "2026-09-01", null, null).get(0);

            assertEquals(1L, vo.getUsedHours(), "只有 used 那 1 格算坐了");
            assertEquals(4L, vo.getBookings(), "总单数含全部 4 条");
            assertEquals(1L, vo.getSeatedBookings());
            assertEquals(1L, vo.getNoShowBookings());
            assertEquals(1L, vo.getCancelledBookings());
        }
    }

    @Test
    @DisplayName("★GZ-BEAN-059 · 分母两条：A 扣关闭（长期+当日，且当日是覆盖不是相加）、B 不扣")
    void seatUsage_denominators() {
        stubUsage("1001");
        try (MockedStatic<TenantHelper> th = mockStatic(TenantHelper.class)) {
            th.when(TenantHelper::getTenantId).thenReturn("1001");
            // 桌型长期关闭 1（每格 effectiveCapacity = 4−1 = 3）
            when(seatTypeConfigMapper.selectList(any())).thenReturn(List.of(
                org.dromara.gz.bean.domain.entity.GzBeanSeatTypeConfig.builder()
                    .id(10L).storeId(1L).seatType("quad").name("四人桌")
                    .bookMode("whole").quantity(4).capacity(1).mpVisible(1).mpLongCloseCount(1).build()));
            // 该日 14:00 那格当日显式关闭 2 → 覆盖掉长期默认 → 该格 effectiveCapacity = 4−2 = 2
            org.dromara.gz.bean.mapper.GzBeanSlotQuotaCloseMapper.CloseRow c =
                new org.dromara.gz.bean.mapper.GzBeanSlotQuotaCloseMapper.CloseRow();
            c.setSeatTypeConfigId(10L);
            c.setSessDate(D1);
            c.setSlotStart(java.time.LocalTime.of(14, 0));
            c.setCloseCount(2);
            when(slotQuotaCloseMapper.selectCloseRowsInRange(any(), any(), any(), any())).thenReturn(List.of(c));

            org.dromara.gz.bean.domain.vo.GzBeanSeatUsageVO vo =
                service.selectSeatUsage("2026-09-01", "2026-09-01", null, null).get(0);

            // 分母 B = 4 格 × 4 = 16（营业容量，不扣任何关闭）
            assertEquals(16L, vo.getOpenHours());
            // 分母 A = 3 格 × (4−1) + 1 格 × (4−2 覆盖) = 9 + 2 = 11
            assertEquals(11L, vo.getSellableHours(), "当日关闭是覆盖长期默认，不是相加（4−2=2，不是 4−1−2=1）");
            assertEquals(0L, vo.getUsedHours());
            assertEquals(0.0d, vo.getOccupancyRate(), "0/11 = 0");
        }
    }

    @Test
    @DisplayName("★GZ-BEAN-059 · 平均每桌/每座时长 = 已上桌时长 ÷ 容量；容量 0 给 null 而不是除零")
    void seatUsage_avgHoursPerUnit() {
        stubUsage("1001");
        try (MockedStatic<TenantHelper> th = mockStatic(TenantHelper.class)) {
            th.when(TenantHelper::getTenantId).thenReturn("1001");
            when(seatTypeConfigMapper.selectList(any())).thenReturn(List.of(
                // 有容量的桌型：2 小时 ÷ 4 桌 = 0.5 小时/桌
                org.dromara.gz.bean.domain.entity.GzBeanSeatTypeConfig.builder()
                    .id(10L).storeId(1L).seatType("single").name("单人")
                    .bookMode("whole").quantity(4).capacity(1).mpVisible(1).build(),
                // 容量 0（数量被调成 0 的历史桌型）：不许出现 Infinity，必须给 null
                org.dromara.gz.bean.domain.entity.GzBeanSeatTypeConfig.builder()
                    .id(20L).storeId(1L).seatType("zero").name("空桌型")
                    .bookMode("whole").quantity(0).capacity(1).mpVisible(1).build()));
            GzBeanBookingMapper.UsageRow zero = usage(D1, "used", "paid",
                java.time.LocalTime.of(10, 0), java.time.LocalTime.of(11, 0), 0);
            zero.setSeatTypeConfigId(20L);
            when(bookingMapper.selectUsageRowsInRange(any(), any(), any(), any())).thenReturn(List.of(
                usage(D1, "used", "paid", java.time.LocalTime.of(10, 0), java.time.LocalTime.of(11, 0), 0),
                usage(D1, "used", "paid", java.time.LocalTime.of(11, 0), java.time.LocalTime.of(12, 0), 0),
                zero));

            List<org.dromara.gz.bean.domain.vo.GzBeanSeatUsageVO> rows =
                service.selectSeatUsage("2026-09-01", "2026-09-01", null, null);

            assertEquals(2, rows.size());
            org.dromara.gz.bean.domain.vo.GzBeanSeatUsageVO cap4 = rows.get(0);
            assertEquals(4L, cap4.getCapacityPerSlot());
            assertEquals(2L, cap4.getUsedHours());
            assertEquals(0.5d, cap4.getAvgHoursPerUnit(), 1e-9, "2 小时 ÷ 4 桌 = 0.5 小时/桌");

            org.dromara.gz.bean.domain.vo.GzBeanSeatUsageVO cap0 = rows.get(1);
            assertEquals(0L, cap0.getCapacityPerSlot());
            assertEquals(1L, cap0.getUsedHours());
            assertNull(cap0.getAvgHoursPerUnit(), "容量 0 → 平均值必须 null（前端显示「—」），不能是 Infinity");
        }
    }

    @Test
    @DisplayName("★GZ-BEAN-059 · 跨月区间按月分行；组单多子单累加；分母 0 时比率为 null")
    void seatUsage_monthBucketsAndGroupRows() {
        stubUsage("1001");
        try (MockedStatic<TenantHelper> th = mockStatic(TenantHelper.class)) {
            th.when(TenantHelper::getTenantId).thenReturn("1001");
            when(bookingMapper.selectUsageRowsInRange(any(), any(), any(), any())).thenReturn(List.of(
                // 组单两条子单：同一格各占 1 单位 → 2 单位·小时
                usage(D1, "used", "paid", java.time.LocalTime.of(10, 0), java.time.LocalTime.of(11, 0), 0),
                usage(D1, "used", "paid", java.time.LocalTime.of(10, 0), java.time.LocalTime.of(11, 0), 0),
                usage(LocalDate.of(2026, 8, 31), "used", "paid", java.time.LocalTime.of(10, 0), java.time.LocalTime.of(11, 0), 0)));

            List<org.dromara.gz.bean.domain.vo.GzBeanSeatUsageVO> rows =
                service.selectSeatUsage("2026-08-31", "2026-09-02", null, null);

            assertEquals(2, rows.size(), "跨月 → 8 月 / 9 月各一行");
            assertEquals("2026-08", rows.get(0).getMonth());
            assertEquals(1L, rows.get(0).getUsedHours());
            assertEquals("2026-09", rows.get(1).getMonth());
            assertEquals(2L, rows.get(1).getUsedHours(), "组单两条子单各占 1 格 → 2 单位·小时");
        }
    }

    @Test
    @DisplayName("★GZ-BEAN-059 · 时长按来源拆：小程序 / 线下（看板现金 walk_in + 后台代客 admin），两段相加 = 总时长")
    void seatUsage_splitsMpAndOfflineHours() {
        stubUsage("1001");
        try (MockedStatic<TenantHelper> th = mockStatic(TenantHelper.class)) {
            th.when(TenantHelper::getTenantId).thenReturn("1001");
            GzBeanBookingMapper.UsageRow mp = usage(D1, "used", "paid",
                java.time.LocalTime.of(10, 0), java.time.LocalTime.of(11, 0), 0);
            mp.setSource("mp");
            GzBeanBookingMapper.UsageRow walkIn = usage(D1, "used", "paid",
                java.time.LocalTime.of(10, 0), java.time.LocalTime.of(12, 0), 0);
            walkIn.setSource("walk_in");
            GzBeanBookingMapper.UsageRow admin = usage(D1, "used", "paid",
                java.time.LocalTime.of(14, 0), java.time.LocalTime.of(15, 0), 0);
            admin.setSource("admin");
            when(bookingMapper.selectUsageRowsInRange(any(), any(), any(), any()))
                .thenReturn(List.of(mp, walkIn, admin));

            org.dromara.gz.bean.domain.vo.GzBeanSeatUsageVO vo =
                service.selectSeatUsage("2026-09-01", "2026-09-01", null, null).get(0);

            assertEquals(4L, vo.getUsedHours(), "1 + 2 + 1");
            assertEquals(1L, vo.getMpHours(), "只有 source=mp 的那张单算小程序");
            assertEquals(3L, vo.getOfflineHours(), "walk_in 2h + admin 1h");
            assertEquals(2L, vo.getOfflineBookings(), "线下的单数（供现金对账）");
            assertEquals(vo.getUsedHours(), vo.getMpHours() + vo.getOfflineHours(),
                "两段相加必须恒等于总时长（否则就是漏算或重复算）");
        }
    }

    @Test
    @DisplayName("★GZ-BEAN-059 · 桌型不存在的日子不计容量（新建「六人桌」不会在历史月份显示成 0% 上桌）")
    void seatUsage_capacityCountedOnlyAfterConfigCreated() {
        stubUsage("1001");
        try (MockedStatic<TenantHelper> th = mockStatic(TenantHelper.class)) {
            th.when(TenantHelper::getTenantId).thenReturn("1001");
            org.dromara.gz.bean.domain.entity.GzBeanSeatTypeConfig cfg =
                org.dromara.gz.bean.domain.entity.GzBeanSeatTypeConfig.builder()
                    .id(10L).storeId(1L).seatType("six").name("六人桌")
                    .bookMode("whole").quantity(4).capacity(1).mpVisible(1).build();
            // @Builder 不含继承字段 → createTime 用 setter 补（2026-09-15 建的桌型）
            cfg.setCreateTime(java.sql.Timestamp.valueOf("2026-09-15 10:00:00"));
            when(seatTypeConfigMapper.selectList(any())).thenReturn(List.of(cfg));
            when(bookingMapper.selectUsageRowsInRange(any(), any(), any(), any())).thenReturn(List.of());

            List<org.dromara.gz.bean.domain.vo.GzBeanSeatUsageVO> rows =
                service.selectSeatUsage("2026-08-01", "2026-09-30", null, null);

            // 8 月整月都在这张桌子存在之前 → 容量 0 且无单 → 整行不出现（而不是显示 0% 上桌）
            assertEquals(1, rows.size(), "只应剩 9 月一行");
            assertEquals("2026-09", rows.get(0).getMonth());
            // 9 月 15~30 共 16 天 × 4 格/天 × 容量 4 = 256
            assertEquals(256L, rows.get(0).getOpenHours(), "容量只从桌型创建那天起算");
            assertEquals(256L, rows.get(0).getSellableHours());
            assertNotNull(rows.get(0).getAvgHoursPerUnit(), "容量存在 → 平均值应是 0.0 而不是 null");
            assertEquals(0.0d, rows.get(0).getAvgHoursPerUnit());
        }
    }

    @Test
    @DisplayName("★GZ-BEAN-059 · 返回顺序 = 月份 → 门店（否则月份会重复两轮，看表的人以为有两个 4 月）")
    void seatUsage_sortedByMonthThenStore() {
        stubUsage("1001");
        try (MockedStatic<TenantHelper> th = mockStatic(TenantHelper.class)) {
            th.when(TenantHelper::getTenantId).thenReturn("1001");
            // 两家门店（循环是门店在外、月份在内 → 天然会是「4月…7月(店1) → 4月…7月(店2)」）
            GzBeanStore s1 = new GzBeanStore();
            s1.setId(1L);
            s1.setName("成都春熙路店");
            s1.setTenantId("1001");
            GzBeanStore s2 = new GzBeanStore();
            s2.setId(2L);
            s2.setName("成都建设路店");
            s2.setTenantId("1001");
            when(storeMapper.selectList(any())).thenReturn(List.of(s1, s2));

            List<org.dromara.gz.bean.domain.vo.GzBeanSeatUsageVO> rows =
                service.selectSeatUsage("2026-04-01", "2026-07-31", null, null);

            List<String> months = rows.stream()
                .map(org.dromara.gz.bean.domain.vo.GzBeanSeatUsageVO::getMonth).toList();
            List<String> sortedMonths = new java.util.ArrayList<>(months);
            java.util.Collections.sort(sortedMonths);
            assertEquals(sortedMonths, months, "月份必须单调不减（月份优先于门店）");

            long april = rows.stream()
                .filter(r -> "2026-04".equals(r.getMonth()))
                .map(org.dromara.gz.bean.domain.vo.GzBeanSeatUsageVO::getStoreId).distinct().count();
            assertEquals(2L, april, "4 月的两家门店必须落在相邻位置（同一月份连续一段）");
        }
    }

    @Test
    @DisplayName("★GZ-BEAN-059 · 比率为 null 而不是除零：该月无营业格（店休）→ 分母 0")
    void seatUsage_zeroDenominatorGivesNullRate() {
        stubUsage("1001");
        when(hourSlotResolver.sliceWindowsToHourSlots(any())).thenReturn(List.of());
        try (MockedStatic<TenantHelper> th = mockStatic(TenantHelper.class)) {
            th.when(TenantHelper::getTenantId).thenReturn("1001");
            when(bookingMapper.selectUsageRowsInRange(any(), any(), any(), any())).thenReturn(List.of(
                usage(D1, "used", "paid", java.time.LocalTime.of(10, 0), java.time.LocalTime.of(11, 0), 0)));

            org.dromara.gz.bean.domain.vo.GzBeanSeatUsageVO vo =
                service.selectSeatUsage("2026-09-01", "2026-09-01", null, null).get(0);

            assertEquals(0L, vo.getSellableHours());
            assertNull(vo.getOccupancyRate(), "分母 0 → null（前端显示「—」），绝不 NaN");
            assertEquals(0L, vo.getUsedHours(), "没有营业格 → 那格不算时长");
        }
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
