package org.dromara.gz.recon.service;

import org.dromara.gz.common.pay.enums.PayBusinessType;
import org.dromara.gz.recon.domain.vo.GzDashboardV11SummaryVO;
import org.dromara.gz.recon.domain.vo.ReconSummaryVo;
import org.dromara.gz.recon.mapper.GzReconDashboardMapper;
import org.dromara.gz.recon.service.impl.GzReconDashboardServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * GZ-ADMIN-106 AC7 — 数据看板 V1.1 聚合 service 单测（Mockito）。
 *
 * <p>验：单接口返所有卡片非空 / 待发货口径（business_status='paid' AND logistics_status='in_japan'）/
 * 业务线 A·B 分流 + 扭蛋平均出货价值（盲盒语义）。本月 GMV/退款/实际到账复用 ADMIN-105（mock IGzReconQueryService）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-ADMIN-106)
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class GzReconDashboardServiceImplTest {

    @Mock
    private GzReconDashboardMapper dashboardMapper;
    @Mock
    private IGzReconQueryService reconQueryService;

    @InjectMocks
    private GzReconDashboardServiceImpl service;

    private void stubReuse() {
        // 复用 ADMIN-105：A 本月 GMV 1000/退款 200/实际到账 740 元；B GMV 500/退款 0/实际到账 470 元（cent）
        lenient().when(reconQueryService.monthlySummary(eq(PayBusinessType.PREORDER), anyString(), anyString()))
            .thenReturn(summary(100000L, 20000L, 74000L));
        lenient().when(reconQueryService.monthlySummary(eq(PayBusinessType.GACHA), anyString(), anyString()))
            .thenReturn(summary(50000L, 0L, 47000L));
    }

    @Test
    @DisplayName("happy：单接口一次返所有卡片字段非空（订单/GMV/退款/实际到账/扭蛋/待发货/热销）")
    void v11Summary_allCardsPresent() {
        stubReuse();
        when(dashboardMapper.todayOrderCount(anyString(), any())).thenReturn(2L);
        when(dashboardMapper.todayGmvCent(anyString(), any())).thenReturn(12345L);
        when(dashboardMapper.gachaOpenCount(anyString())).thenReturn(4L);
        when(dashboardMapper.gachaSumValueCent(anyString())).thenReturn(40000L);
        when(dashboardMapper.pendingShipCount()).thenReturn(3L);
        GzDashboardV11SummaryVO.TopProduct tp = new GzDashboardV11SummaryVO.TopProduct();
        tp.setProductId(1L);
        tp.setName("热销手办");
        tp.setSalesCount(99L);
        when(dashboardMapper.topProducts(anyInt())).thenReturn(List.of(tp));

        GzDashboardV11SummaryVO vo = service.getV11Summary();

        assertEquals(74000L, vo.getMonthSettleCentPreorder(), "本月实际到账复用 ADMIN-105");
        assertEquals(47000L, vo.getMonthSettleCentGacha());
        assertEquals(4L, vo.getGachaOpenCount());
        assertEquals(3L, vo.getPendingShipCount());
        assertNotNull(vo.getTopProducts());
        assertEquals(1, vo.getTopProducts().size());
        assertEquals("热销手办", vo.getTopProducts().get(0).getName());
    }

    @Test
    @DisplayName("待发货口径：pendingShipCount 直接取 mapper（business_status='paid' AND logistics_status='in_japan'）")
    void v11Summary_pendingShip() {
        stubReuse();
        when(dashboardMapper.todayOrderCount(anyString(), any())).thenReturn(0L);
        when(dashboardMapper.todayGmvCent(anyString(), any())).thenReturn(0L);
        when(dashboardMapper.gachaOpenCount(anyString())).thenReturn(0L);
        when(dashboardMapper.gachaSumValueCent(anyString())).thenReturn(0L);
        when(dashboardMapper.pendingShipCount()).thenReturn(5L);
        when(dashboardMapper.topProducts(anyInt())).thenReturn(List.of());

        assertEquals(5L, service.getV11Summary().getPendingShipCount());
    }

    @Test
    @DisplayName("业务线 A/B 分流 + 扭蛋平均出货价值 = SUM/开盒数（整除取整）")
    void v11Summary_lineSplitAndGachaAvg() {
        stubReuse();
        when(dashboardMapper.todayOrderCount(PayBusinessType.PREORDER, LocalDate.now())).thenReturn(3L);
        when(dashboardMapper.todayOrderCount(PayBusinessType.GACHA, LocalDate.now())).thenReturn(2L);
        when(dashboardMapper.todayGmvCent(PayBusinessType.PREORDER, LocalDate.now())).thenReturn(30000L);
        when(dashboardMapper.todayGmvCent(PayBusinessType.GACHA, LocalDate.now())).thenReturn(10000L);
        when(dashboardMapper.gachaOpenCount(anyString())).thenReturn(4L);
        when(dashboardMapper.gachaSumValueCent(anyString())).thenReturn(40000L); // 平均 10000
        when(dashboardMapper.pendingShipCount()).thenReturn(0L);
        when(dashboardMapper.topProducts(anyInt())).thenReturn(List.of());

        GzDashboardV11SummaryVO vo = service.getV11Summary();
        assertEquals(3L, vo.getTodayOrderCountPreorder());
        assertEquals(2L, vo.getTodayOrderCountGacha());
        assertEquals(30000L, vo.getTodayGmvCentPreorder());
        assertEquals(10000L, vo.getTodayGmvCentGacha());
        assertEquals(10000L, vo.getGachaAvgValueCent(), "40000 / 4");
    }

    private static ReconSummaryVo summary(long gmv, long refund, long settle) {
        ReconSummaryVo vo = new ReconSummaryVo();
        vo.setGmvCent(gmv);
        vo.setRefundCent(refund);
        vo.setSettleCent(settle);
        return vo;
    }
}
