package org.dromara.gz.ord.service;

import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.gz.ord.domain.dto.LogisticsCarrierUpdateDto;
import org.dromara.gz.ord.domain.dto.LogisticsForwardDto;
import org.dromara.gz.ord.domain.dto.LogisticsRollbackDto;
import org.dromara.gz.ord.domain.vo.LogisticsOrderStateVo;
import org.dromara.gz.ord.mapper.GzLogisticsMapper;
import org.dromara.gz.ord.service.impl.GzLogisticsServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * GZ-ADMIN-104 AC11 — 跨境物流 2 态推进 service 单测（Mockito，≥6 raw assertion）。
 *
 * <p>验状态机 + 必录校验 + 9 项快递校验 + E6 退款拦截 + 审计写入（doc/10 §9）。owner 回退权限
 * 由 controller {@code @SaCheckPermission("gz:ord:logistics:rollback")} 门控（非 service 层，Playwright/集成覆盖）；
 * reason 非空由 DTO {@code @NotBlank} + controller {@code @Validated} 拦截（同上）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-ADMIN-104)
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class GzLogisticsServiceImplTest {

    @Mock
    private GzLogisticsMapper logisticsMapper;

    @InjectMocks
    private GzLogisticsServiceImpl service;

    private LogisticsForwardDto forwardDto(String carrier, String tracking) {
        LogisticsForwardDto d = new LogisticsForwardDto();
        d.setBusinessType("preorder");
        d.setBusinessOrderNo("PREORD-20260601-000001");
        d.setCnCarrierCode(carrier);
        d.setCnTrackingNo(tracking);
        return d;
    }

    private LogisticsOrderStateVo state(String logistics, String business) {
        LogisticsOrderStateVo s = new LogisticsOrderStateVo();
        s.setLogisticsStatus(logistics);
        s.setBusinessStatus(business);
        return s;
    }

    @Test
    @DisplayName("happy：in_japan→in_china_dispatching 录合法快递+单号 → 字段更新 + status_forward 审计")
    void forward_inJapanToDispatching_happy() {
        when(logisticsMapper.selectState("gz_ord_order", "PREORD-20260601-000001")).thenReturn(state("in_japan", "paid"));
        when(logisticsMapper.forwardToDispatching(eq("gz_ord_order"), any(), eq("sf"), eq("SF123"))).thenReturn(1);

        try (MockedStatic<LoginHelper> lh = mockStatic(LoginHelper.class)) {
            lh.when(LoginHelper::getUsername).thenReturn("tester");
            lh.when(LoginHelper::getTenantId).thenReturn("1001");
            service.forward(forwardDto("sf", "SF123"));
        }

        verify(logisticsMapper).forwardToDispatching("gz_ord_order", "PREORD-20260601-000001", "sf", "SF123");
        verify(logisticsMapper).insertAudit(eq("preorder"), any(), eq("status_forward"),
            eq("in_japan"), eq("in_china_dispatching"), any(), any(), eq("sf"), eq("SF123"), any(), eq("admin"), any(), eq("1001"));
    }

    @Test
    @DisplayName("漏录单号（cn_tracking_no 空）→ 报错，不更新")
    void forward_missingTracking_throws() {
        when(logisticsMapper.selectState(any(), any())).thenReturn(state("in_japan", "paid"));
        assertThrows(ServiceException.class, () -> service.forward(forwardDto("sf", "")));
        verify(logisticsMapper, never()).forwardToDispatching(any(), any(), any(), any());
    }

    @Test
    @DisplayName("非法快递编码（不在 9 项）→ 报错，不更新")
    void forward_invalidCarrier_throws() {
        when(logisticsMapper.selectState(any(), any())).thenReturn(state("in_japan", "paid"));
        assertThrows(ServiceException.class, () -> service.forward(forwardDto("dhl", "DHL999")));
        verify(logisticsMapper, never()).forwardToDispatching(any(), any(), any(), any());
    }

    @Test
    @DisplayName("E6：订单 business_status=refunded 推进 → 报错")
    void forward_refunded_throws() {
        when(logisticsMapper.selectState(any(), any())).thenReturn(state("in_japan", "refunded"));
        assertThrows(ServiceException.class, () -> service.forward(forwardDto("sf", "SF123")));
        verify(logisticsMapper, never()).forwardToDispatching(any(), any(), any(), any());
    }

    @Test
    @DisplayName("in_china_dispatching→delivered happy → delivered_time + status_forward 审计")
    void forward_dispatchingToDelivered_happy() {
        when(logisticsMapper.selectState(any(), any())).thenReturn(state("in_china_dispatching", "paid"));
        when(logisticsMapper.forwardToDelivered(eq("gz_ord_order"), any())).thenReturn(1);

        try (MockedStatic<LoginHelper> lh = mockStatic(LoginHelper.class)) {
            lh.when(LoginHelper::getUsername).thenReturn("tester");
            lh.when(LoginHelper::getTenantId).thenReturn("1001");
            service.forward(forwardDto(null, null));
        }

        verify(logisticsMapper).forwardToDelivered(eq("gz_ord_order"), any());
        verify(logisticsMapper).insertAudit(any(), any(), eq("status_forward"),
            eq("in_china_dispatching"), eq("delivered"), any(), any(), any(), any(), any(), eq("admin"), any(), any());
    }

    @Test
    @DisplayName("已 delivered 终态再推进 → 报错")
    void forward_delivered_throws() {
        when(logisticsMapper.selectState(any(), any())).thenReturn(state("delivered", "paid"));
        assertThrows(ServiceException.class, () -> service.forward(forwardDto(null, null)));
        verify(logisticsMapper, never()).forwardToDelivered(any(), any());
    }

    @Test
    @DisplayName("改单号：carrier_update 审计记 from/to（仅 in_china_dispatching 可改）")
    void updateCarrier_happy() {
        LogisticsOrderStateVo s = state("in_china_dispatching", "paid");
        s.setCnCarrierCode("yto");
        s.setCnTrackingNo("YT000");
        when(logisticsMapper.selectState(any(), any())).thenReturn(s);
        when(logisticsMapper.updateCarrier(eq("gz_ord_order"), any(), eq("sf"), eq("SF999"))).thenReturn(1);

        LogisticsCarrierUpdateDto dto = new LogisticsCarrierUpdateDto();
        dto.setBusinessType("preorder");
        dto.setBusinessOrderNo("PREORD-20260601-000001");
        dto.setCnCarrierCode("sf");
        dto.setCnTrackingNo("SF999");

        try (MockedStatic<LoginHelper> lh = mockStatic(LoginHelper.class)) {
            lh.when(LoginHelper::getUsername).thenReturn("tester");
            lh.when(LoginHelper::getTenantId).thenReturn("1001");
            service.updateCarrier(dto);
        }

        verify(logisticsMapper).insertAudit(any(), any(), eq("carrier_update"),
            any(), any(), eq("yto"), eq("YT000"), eq("sf"), eq("SF999"), any(), eq("admin"), any(), any());
    }

    @Test
    @DisplayName("改单号：订单不在派送中 → 报错")
    void updateCarrier_notDispatching_throws() {
        when(logisticsMapper.selectState(any(), any())).thenReturn(state("in_japan", "paid"));
        LogisticsCarrierUpdateDto dto = new LogisticsCarrierUpdateDto();
        dto.setBusinessType("preorder");
        dto.setBusinessOrderNo("PREORD-20260601-000001");
        dto.setCnCarrierCode("sf");
        dto.setCnTrackingNo("SF999");
        assertThrows(ServiceException.class, () -> service.updateCarrier(dto));
        verify(logisticsMapper, never()).updateCarrier(any(), any(), any(), any());
    }

    @Test
    @DisplayName("owner 回退：delivered→in_china_dispatching happy，status_rollback 记 reason")
    void rollback_happy() {
        when(logisticsMapper.selectState(any(), any())).thenReturn(state("delivered", "paid"));
        when(logisticsMapper.rollback(eq("gz_ord_order"), any(), eq("delivered"), eq("in_china_dispatching"))).thenReturn(1);

        LogisticsRollbackDto dto = new LogisticsRollbackDto();
        dto.setBusinessType("preorder");
        dto.setBusinessOrderNo("PREORD-20260601-000001");
        dto.setReason("录错签收，回退");

        try (MockedStatic<LoginHelper> lh = mockStatic(LoginHelper.class)) {
            lh.when(LoginHelper::getUsername).thenReturn("owner");
            lh.when(LoginHelper::getTenantId).thenReturn("1001");
            service.rollback(dto);
        }

        verify(logisticsMapper).insertAudit(any(), any(), eq("status_rollback"),
            eq("delivered"), eq("in_china_dispatching"), any(), any(), any(), any(), eq("录错签收，回退"), eq("admin"), any(), any());
    }

    @Test
    @DisplayName("回退：已是初始态 in_japan → 报错")
    void rollback_fromInJapan_throws() {
        when(logisticsMapper.selectState(any(), any())).thenReturn(state("in_japan", "paid"));
        LogisticsRollbackDto dto = new LogisticsRollbackDto();
        dto.setBusinessType("preorder");
        dto.setBusinessOrderNo("PREORD-20260601-000001");
        dto.setReason("x");
        assertThrows(ServiceException.class, () -> service.rollback(dto));
        verify(logisticsMapper, never()).rollback(any(), any(), any(), any());
    }

    @Test
    @DisplayName("gacha 业务线 → 路由到 gz_gacha_order 表")
    void forward_gachaRoutesToGachaTable() {
        LogisticsForwardDto d = forwardDto("sf", "SF123");
        d.setBusinessType("gacha");
        d.setBusinessOrderNo("GACHA-20260601-000001");
        when(logisticsMapper.selectState("gz_gacha_order", "GACHA-20260601-000001")).thenReturn(state("in_japan", "paid"));
        when(logisticsMapper.forwardToDispatching(eq("gz_gacha_order"), any(), any(), any())).thenReturn(1);

        try (MockedStatic<LoginHelper> lh = mockStatic(LoginHelper.class)) {
            lh.when(LoginHelper::getUsername).thenReturn("tester");
            lh.when(LoginHelper::getTenantId).thenReturn("1001");
            service.forward(d);
        }

        verify(logisticsMapper, times(1)).forwardToDispatching(eq("gz_gacha_order"), any(), any(), any());
    }
}
