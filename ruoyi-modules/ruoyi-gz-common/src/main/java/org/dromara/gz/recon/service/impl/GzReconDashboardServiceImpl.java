package org.dromara.gz.recon.service.impl;

import lombok.RequiredArgsConstructor;
import org.dromara.gz.common.pay.enums.PayBusinessType;
import org.dromara.gz.recon.domain.vo.GzDashboardV11SummaryVO;
import org.dromara.gz.recon.domain.vo.ReconSummaryVo;
import org.dromara.gz.recon.mapper.GzReconDashboardMapper;
import org.dromara.gz.recon.service.IGzReconDashboardService;
import org.dromara.gz.recon.service.IGzReconQueryService;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.YearMonth;

/**
 * GZ-ADMIN-106 数据看板 V1.1 service 实现。
 *
 * @author kevin-coder (sensenran-guzi · GZ-ADMIN-106)
 */
@Service
@RequiredArgsConstructor
public class GzReconDashboardServiceImpl implements IGzReconDashboardService {

    /** 热销榜取前 N。 */
    private static final int TOP_N = 10;

    private final GzReconDashboardMapper dashboardMapper;
    /** 复用 ADMIN-105 对账查询 service（本月 GMV / 退款 / 实际到账单一口径，不重写 4% 计算）。 */
    private final IGzReconQueryService reconQueryService;

    @Override
    public GzDashboardV11SummaryVO getV11Summary() {
        LocalDate today = LocalDate.now();
        String month = YearMonth.now().toString(); // yyyy-MM

        GzDashboardV11SummaryVO vo = new GzDashboardV11SummaryVO();

        // 今日订单数 / GMV（A / B，自有聚合）
        vo.setTodayOrderCountPreorder(dashboardMapper.todayOrderCount(PayBusinessType.PREORDER, today));
        vo.setTodayOrderCountGacha(dashboardMapper.todayOrderCount(PayBusinessType.GACHA, today));
        vo.setTodayGmvCentPreorder(dashboardMapper.todayGmvCent(PayBusinessType.PREORDER, today));
        vo.setTodayGmvCentGacha(dashboardMapper.todayGmvCent(PayBusinessType.GACHA, today));

        // 本月 GMV / 退款 / 实际到账（A / B，复用 ADMIN-105，单一口径）
        ReconSummaryVo a = reconQueryService.monthlySummary(PayBusinessType.PREORDER, month, month);
        ReconSummaryVo b = reconQueryService.monthlySummary(PayBusinessType.GACHA, month, month);
        vo.setMonthGmvCentPreorder(nz(a.getGmvCent()));
        vo.setMonthGmvCentGacha(nz(b.getGmvCent()));
        vo.setMonthRefundCentPreorder(nz(a.getRefundCent()));
        vo.setMonthRefundCentGacha(nz(b.getRefundCent()));
        vo.setMonthSettleCentPreorder(nz(a.getSettleCent()));
        vo.setMonthSettleCentGacha(nz(b.getSettleCent()));

        // 扭蛋本月开盒数 + 平均出货价值（盲盒语义）
        long openCount = dashboardMapper.gachaOpenCount(month);
        long sumValue = dashboardMapper.gachaSumValueCent(month);
        vo.setGachaOpenCount(openCount);
        vo.setGachaAvgValueCent(openCount == 0 ? 0 : sumValue / openCount);

        // 待发货订单数（提醒甲方推进物流）
        vo.setPendingShipCount(dashboardMapper.pendingShipCount());

        // 热销预购 Top10
        vo.setTopProducts(dashboardMapper.topProducts(TOP_N));

        return vo;
    }

    private static long nz(Long v) {
        return v == null ? 0L : v;
    }
}
