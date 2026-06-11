package org.dromara.gz.common.pay.controller.applet;

import org.dromara.common.core.domain.R;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.gz.common.pay.domain.vo.GzPayStatusVO;
import org.dromara.gz.common.pay.domain.vo.GzPayTransactionVO;
import org.dromara.gz.common.pay.service.IGzPayTransactionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * GZ-PAY-001 mp 支付状态轮询 IDOR 收口单测（D16 B2）。
 */
@Tag("dev")
class GzPayMpControllerTest {

    private GzPayTransactionVO ownerVo() {
        GzPayTransactionVO vo = new GzPayTransactionVO();
        vo.setId(9L);
        vo.setOutTradeNo("PINDOU-20260611-000001");
        vo.setBusinessType("pindou");
        vo.setUserId(100L);
        vo.setOpenid("o_secret_openid");
        vo.setTransactionId("wx_txn_123");
        vo.setPrepayId("prepay_secret");
        vo.setAmountCent(1500L);
        vo.setStatus("paid");
        return vo;
    }

    @Test
    @DisplayName("本人查自己支付单 → 200 + 窄 VO（不含 openid/prepayId/userId）")
    void status_owner_ok() {
        IGzPayTransactionService svc = mock(IGzPayTransactionService.class);
        when(svc.getByOutTradeNo("PINDOU-20260611-000001")).thenReturn(ownerVo());
        GzPayMpController c = new GzPayMpController(svc);

        try (MockedStatic<LoginHelper> lh = mockStatic(LoginHelper.class)) {
            lh.when(LoginHelper::getUserId).thenReturn(100L);
            R<GzPayStatusVO> r = c.status("PINDOU-20260611-000001");
            assertEquals(200, r.getCode());
            GzPayStatusVO data = r.getData();
            assertNotNull(data);
            assertEquals("paid", data.getStatus());
            assertEquals(1500L, data.getAmountCent());
            // 窄 VO 结构性不含 openid/prepayId/userId —— 仅暴露 transactionId（本人单）
            assertEquals("wx_txn_123", data.getTransactionId());
        }
    }

    @Test
    @DisplayName("枚举他人 out_trade_no → 403 不泄露任何数据（IDOR 封堵）")
    void status_otherUser_forbidden() {
        IGzPayTransactionService svc = mock(IGzPayTransactionService.class);
        when(svc.getByOutTradeNo("PINDOU-20260611-000001")).thenReturn(ownerVo());
        GzPayMpController c = new GzPayMpController(svc);

        try (MockedStatic<LoginHelper> lh = mockStatic(LoginHelper.class)) {
            lh.when(LoginHelper::getUserId).thenReturn(200L); // 非单主
            R<GzPayStatusVO> r = c.status("PINDOU-20260611-000001");
            assertEquals(403, r.getCode());
            assertNull(r.getData());
        }
    }

    @Test
    @DisplayName("单不存在 → fail 订单不存在")
    void status_notFound() {
        IGzPayTransactionService svc = mock(IGzPayTransactionService.class);
        when(svc.getByOutTradeNo("X")).thenReturn(null);
        GzPayMpController c = new GzPayMpController(svc);

        try (MockedStatic<LoginHelper> lh = mockStatic(LoginHelper.class)) {
            lh.when(LoginHelper::getUserId).thenReturn(100L);
            R<GzPayStatusVO> r = c.status("X");
            assertNull(r.getData());
            assertEquals("订单不存在", r.getMsg());
        }
    }

    @Test
    @DisplayName("未登录 → 401")
    void status_notLogin() {
        IGzPayTransactionService svc = mock(IGzPayTransactionService.class);
        GzPayMpController c = new GzPayMpController(svc);

        try (MockedStatic<LoginHelper> lh = mockStatic(LoginHelper.class)) {
            lh.when(LoginHelper::getUserId).thenReturn(null);
            R<GzPayStatusVO> r = c.status("any");
            assertEquals(401, r.getCode());
        }
    }
}
