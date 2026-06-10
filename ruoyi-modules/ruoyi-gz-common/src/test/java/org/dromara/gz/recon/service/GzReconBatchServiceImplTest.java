package org.dromara.gz.recon.service;

import org.dromara.common.core.service.ConfigService;
import org.dromara.gz.common.pay.enums.PayBusinessType;
import org.dromara.gz.recon.domain.entity.GzReconDaily;
import org.dromara.gz.recon.domain.entity.GzReconMonthly;
import org.dromara.gz.recon.domain.entity.GzReconSettle;
import org.dromara.gz.recon.domain.vo.ReconSummaryVo;
import org.dromara.gz.recon.mapper.GzReconDailyMapper;
import org.dromara.gz.recon.mapper.GzReconMonthlyMapper;
import org.dromara.gz.recon.mapper.GzReconSettleMapper;
import org.dromara.gz.recon.mapper.GzReconSourceMapper;
import org.dromara.gz.recon.service.impl.GzReconBatchServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * GZ-ADMIN-105 AC10 — 对账跑批 service 业务规则单测（Mockito 在内存层模拟数据源 + recon 表）。
 *
 * <p>验合同 §4.1 4% 分成兑现的编排正确性（doc/11 §9.1/§9.2/§9.4 / doc/10 Q10.4 逐字）：</p>
 * <ul>
 *   <li>runDaily：A/B 各一条、daily 层 settle 不 MAX0（可负，如实记差值）、<b>test 单 exclude</b>（从不查 'test'）；</li>
 *   <li>runMonthly：settle = MAX(0, gmv−refund−fee)、commission 向下取整、<b>A/B 不交叉冲抵</b>（A 负流水归零不冲减 B）；</li>
 *   <li>runQuarterly：commission_total（A+B 相加）+ maintenance（300000×3）= payable。</li>
 * </ul>
 *
 * <p>纯函数算法（settle MAX0 / commission floor 边界）另见 {@link ReconCalculatorTest}。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-ADMIN-105)
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class GzReconBatchServiceImplTest {

    @Mock
    private GzReconSourceMapper sourceMapper;
    @Mock
    private GzReconDailyMapper dailyMapper;
    @Mock
    private GzReconMonthlyMapper monthlyMapper;
    @Mock
    private GzReconSettleMapper settleMapper;
    @Mock
    private ConfigService configService;

    @InjectMocks
    private GzReconBatchServiceImpl service;

    // ------------------------------------------------------------------ runDaily

    @Test
    @DisplayName("runDaily：A/B 各一条 daily，金额逐字落地；test 单从不被查询（exclude）")
    void runDaily_perBusinessType_excludeTest() {
        LocalDate day = LocalDate.of(2026, 6, 1);
        // mock 流水：A=preorder（GMV 100 元 / 退款 20 元 / 通道费 6 元）；B=gacha（GMV 50 元 / 退款 0 / 通道费 3 元）
        when(sourceMapper.sumGmvCent(PayBusinessType.PREORDER, day)).thenReturn(10000L);
        when(sourceMapper.sumRefundCent(PayBusinessType.PREORDER, day)).thenReturn(2000L);
        when(sourceMapper.sumFeeCent(PayBusinessType.PREORDER, day)).thenReturn(600L);
        when(sourceMapper.sumGmvCent(PayBusinessType.GACHA, day)).thenReturn(5000L);
        when(sourceMapper.sumRefundCent(PayBusinessType.GACHA, day)).thenReturn(0L);
        when(sourceMapper.sumFeeCent(PayBusinessType.GACHA, day)).thenReturn(300L);

        int count = service.runDaily(day);
        assertEquals(2, count, "A/B 各一条");

        ArgumentCaptor<GzReconDaily> cap = ArgumentCaptor.forClass(GzReconDaily.class);
        verify(dailyMapper, times(2)).upsert(cap.capture());
        List<GzReconDaily> rows = cap.getAllValues();

        GzReconDaily a = rows.stream().filter(r -> PayBusinessType.PREORDER.equals(r.getBusinessType())).findFirst().orElseThrow();
        assertEquals(10000L, a.getSystemGmvCent());
        assertEquals(2000L, a.getSystemRefundCent());
        assertEquals(600L, a.getSystemFeeCent());
        assertEquals(7400L, a.getSystemSettleCent(), "settle = 10000 − 2000 − 600");
        assertEquals("1001", a.getTenantId(), "cron 上下文显式落租户 1001");

        GzReconDaily b = rows.stream().filter(r -> PayBusinessType.GACHA.equals(r.getBusinessType())).findFirst().orElseThrow();
        assertEquals(4700L, b.getSystemSettleCent(), "settle = 5000 − 0 − 300");

        // test 单 exclude：跑批只迭代 preorder/gacha，从不查 'test'
        verify(sourceMapper, never()).sumGmvCent(eq(PayBusinessType.TEST), any());
    }

    @Test
    @DisplayName("runDaily：daily 层 settle 不 MAX0（负流水如实记录可负，月度层才归零）")
    void runDaily_negativeKeptAtDailyLayer() {
        LocalDate day = LocalDate.of(2026, 6, 2);
        // A 当日：GMV 10 元 − 退款 20 元 − 通道费 6 元 = −16 元（daily 如实记 −1600）
        when(sourceMapper.sumGmvCent(PayBusinessType.PREORDER, day)).thenReturn(1000L);
        when(sourceMapper.sumRefundCent(PayBusinessType.PREORDER, day)).thenReturn(2000L);
        when(sourceMapper.sumFeeCent(PayBusinessType.PREORDER, day)).thenReturn(600L);
        when(sourceMapper.sumGmvCent(PayBusinessType.GACHA, day)).thenReturn(0L);
        when(sourceMapper.sumRefundCent(PayBusinessType.GACHA, day)).thenReturn(0L);
        when(sourceMapper.sumFeeCent(PayBusinessType.GACHA, day)).thenReturn(0L);

        service.runDaily(day);

        ArgumentCaptor<GzReconDaily> cap = ArgumentCaptor.forClass(GzReconDaily.class);
        verify(dailyMapper, times(2)).upsert(cap.capture());
        GzReconDaily a = cap.getAllValues().stream()
            .filter(r -> PayBusinessType.PREORDER.equals(r.getBusinessType())).findFirst().orElseThrow();
        assertEquals(-1600L, a.getSystemSettleCent(), "daily 层不 MAX0，如实记 −1600");
    }

    // ------------------------------------------------------------------ runMonthly

    @Test
    @DisplayName("runMonthly happy：A 当月聚合 → settle=MAX0 / commission=floor(settle×400/10000)")
    void runMonthly_happyPath() {
        YearMonth month = YearMonth.of(2026, 6);
        stubRate(400, 400);
        // A 当月：GMV 1000 元 / 退款 200 元 / 通道费 60 元 → settle 740 元 = 74000 分 → 分成 2960 分
        when(dailyMapper.sumDailyByMonth(PayBusinessType.PREORDER, "2026-06"))
            .thenReturn(summary(100000L, 20000L, 6000L));
        // B 当月：GMV 500 元 / 退款 0 / 通道费 30 元 → settle 470 元 = 47000 分 → 分成 1880 分
        when(dailyMapper.sumDailyByMonth(PayBusinessType.GACHA, "2026-06"))
            .thenReturn(summary(50000L, 0L, 3000L));

        int count = service.runMonthly(month);
        assertEquals(2, count);

        ArgumentCaptor<GzReconMonthly> cap = ArgumentCaptor.forClass(GzReconMonthly.class);
        verify(monthlyMapper, times(2)).upsert(cap.capture());

        GzReconMonthly a = pick(cap.getAllValues(), PayBusinessType.PREORDER);
        assertEquals(74000L, a.getSettleCent());
        assertEquals(400, a.getCommissionRateBp());
        assertEquals(2960L, a.getCommissionCent(), "74000 × 400 / 10000 = 2960");

        GzReconMonthly b = pick(cap.getAllValues(), PayBusinessType.GACHA);
        assertEquals(47000L, b.getSettleCent());
        assertEquals(1880L, b.getCommissionCent(), "47000 × 400 / 10000 = 1880");
    }

    @Test
    @DisplayName("runMonthly A/B 不交叉冲抵：A 负流水→A settle=0/分成=0，不冲减 B 的分成基数（B 照常算）")
    void runMonthly_noCrossOffset() {
        YearMonth month = YearMonth.of(2026, 6);
        stubRate(400, 400);
        // A 当月负流水：GMV 100 元 − 退款 200 元 − 通道费 6 元 = −106 元 → settle MAX0 = 0、分成 0
        when(dailyMapper.sumDailyByMonth(PayBusinessType.PREORDER, "2026-06"))
            .thenReturn(summary(10000L, 20000L, 600L));
        // B 当月正流水：GMV 500 元 − 通道费 30 元 = 470 元 → settle 47000、分成 1880（不被 A 负流水冲减）
        when(dailyMapper.sumDailyByMonth(PayBusinessType.GACHA, "2026-06"))
            .thenReturn(summary(50000L, 0L, 3000L));

        service.runMonthly(month);

        ArgumentCaptor<GzReconMonthly> cap = ArgumentCaptor.forClass(GzReconMonthly.class);
        verify(monthlyMapper, times(2)).upsert(cap.capture());

        GzReconMonthly a = pick(cap.getAllValues(), PayBusinessType.PREORDER);
        assertEquals(0L, a.getSettleCent(), "A 负流水该月 settle = MAX0 = 0（不倒贴）");
        assertEquals(0L, a.getCommissionCent(), "A 分成 0");

        GzReconMonthly b = pick(cap.getAllValues(), PayBusinessType.GACHA);
        assertEquals(47000L, b.getSettleCent(), "B 不受 A 负流水冲减");
        assertEquals(1880L, b.getCommissionCent(), "B 照常算分成（A/B 独立核算，合同 §4.1.2）");
    }

    @Test
    @DisplayName("runMonthly commission 向下取整（非整 4% 倍数）+ 分成比例走 sys_config（非 400 场景）")
    void runMonthly_floorAndConfigurableRate() {
        YearMonth month = YearMonth.of(2026, 6);
        // A 用 400、B 重谈到 500（5%）
        stubRate(400, 500);
        // A：settle = 7401 分（GMV 7401 / 无退款 / 无费）→ 7401 × 400 / 10000 = 296.04 → floor 296
        when(dailyMapper.sumDailyByMonth(PayBusinessType.PREORDER, "2026-06"))
            .thenReturn(summary(7401L, 0L, 0L));
        // B：settle = 10000 分 → 10000 × 500 / 10000 = 500
        when(dailyMapper.sumDailyByMonth(PayBusinessType.GACHA, "2026-06"))
            .thenReturn(summary(10000L, 0L, 0L));

        service.runMonthly(month);

        ArgumentCaptor<GzReconMonthly> cap = ArgumentCaptor.forClass(GzReconMonthly.class);
        verify(monthlyMapper, times(2)).upsert(cap.capture());
        assertEquals(296L, pick(cap.getAllValues(), PayBusinessType.PREORDER).getCommissionCent(), "向下取整 296（非 296.04 四舍五入）");
        assertEquals(500L, pick(cap.getAllValues(), PayBusinessType.GACHA).getCommissionCent());
        assertEquals(500, pick(cap.getAllValues(), PayBusinessType.GACHA).getCommissionRateBp(), "B 取 sys_config 500");
    }

    // ------------------------------------------------------------------ runQuarterly

    @Test
    @DisplayName("runQuarterly：commission_total(A+B 相加) + maintenance(300000×3) = payable_total")
    void runQuarterly_settle() {
        // 2026-Q2 = [2026-04, 2026-05, 2026-06]；当季 monthly.commission_cent 合计（A+B）= 88880 分
        when(monthlyMapper.sumCommissionByMonths(List.of("2026-04", "2026-05", "2026-06"))).thenReturn(88880L);
        when(configService.getConfigValue("gz.commission.maintenance.monthly.cent")).thenReturn("300000");

        int count = service.runQuarterly("2026-Q2");
        assertEquals(1, count);

        ArgumentCaptor<GzReconSettle> cap = ArgumentCaptor.forClass(GzReconSettle.class);
        verify(settleMapper).upsert(cap.capture());
        GzReconSettle s = cap.getValue();
        assertEquals("2026-Q2", s.getQuarter());
        assertEquals(88880L, s.getCommissionTotalCent());
        assertEquals(900000L, s.getMaintenanceTotalCent(), "月维护费 300000 × 3");
        assertEquals(988880L, s.getPayableTotalCent(), "88880 + 900000");
    }

    @Test
    @DisplayName("runQuarterly：月维护费走 sys_config，缺失兜底 300000（¥3000）")
    void runQuarterly_maintenanceConfigFallback() {
        when(monthlyMapper.sumCommissionByMonths(any())).thenReturn(0L);
        when(configService.getConfigValue("gz.commission.maintenance.monthly.cent")).thenReturn(null);

        service.runQuarterly("2026-Q1");

        ArgumentCaptor<GzReconSettle> cap = ArgumentCaptor.forClass(GzReconSettle.class);
        verify(settleMapper).upsert(cap.capture());
        assertEquals(900000L, cap.getValue().getMaintenanceTotalCent(), "sys_config 缺失兜底 300000×3");
    }

    // ------------------------------------------------------------------ helpers

    private void stubRate(int preorderBp, int gachaBp) {
        lenient().when(configService.getConfigValue("gz.commission.rate.preorder")).thenReturn(String.valueOf(preorderBp));
        lenient().when(configService.getConfigValue("gz.commission.rate.gacha")).thenReturn(String.valueOf(gachaBp));
    }

    private static ReconSummaryVo summary(long gmv, long refund, long fee) {
        ReconSummaryVo vo = new ReconSummaryVo();
        vo.setGmvCent(gmv);
        vo.setRefundCent(refund);
        vo.setChannelFeeCent(fee);
        return vo;
    }

    private static GzReconMonthly pick(List<GzReconMonthly> rows, String businessType) {
        return rows.stream().filter(r -> businessType.equals(r.getBusinessType())).findFirst().orElseThrow();
    }
}
