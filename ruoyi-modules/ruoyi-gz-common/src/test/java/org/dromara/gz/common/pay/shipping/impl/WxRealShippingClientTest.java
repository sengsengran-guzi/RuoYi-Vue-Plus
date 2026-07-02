package org.dromara.gz.common.pay.shipping.impl;

import org.dromara.gz.common.pay.shipping.WxShippingClient.UploadResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link WxRealShippingClient#interpret} 单测 —— 发货上报错误码归类（无 Spring / 无网络）。
 *
 * <p><b>核心回归</b>：{@code 10060023}「已上传物流信息（发货信息未更新）」必须判为幂等成功。
 * 历史 74 单卡 failed 的根因就是这里把 10060023 当失败 → 店家已在小程序订单中心手动发货、重试仍永回 failed，
 * 状态永不收敛。本测试锁死该行为，防回归。</p>
 *
 * @author kevin-coder (sensenran-guzi)
 */
@Tag("dev")
class WxRealShippingClientTest {

    /** interpret() 不触及 accessTokenManager，构造传 null 即可。 */
    private final WxRealShippingClient client = new WxRealShippingClient(null);

    @Test
    @DisplayName("errcode 0 / null → 成功")
    void okOnZeroOrNull() {
        assertTrue(client.interpret(0, "ok").success());
        assertTrue(client.interpret(null, null).success());
    }

    @Test
    @DisplayName("10060023「发货信息未更新」→ 幂等成功（核心回归，历史卡单根因）")
    void idempotentOnShippingNotUpdated() {
        UploadResult r = client.interpret(10060023, "发货信息未更新");
        assertTrue(r.success(), "10060023 必须判为幂等成功，否则已发货单永卡 failed");
    }

    @Test
    @DisplayName("268440065「订单已发货」→ 幂等成功")
    void idempotentOnAlreadyShipped() {
        assertTrue(client.interpret(268440065, "订单已发货").success());
    }

    @Test
    @DisplayName("errmsg 含「已发货」→ 幂等成功（错误码变体兜底）")
    void idempotentOnErrmsgKeyword() {
        assertTrue(client.interpret(999999, "该订单已发货，请勿重复上传").success());
        assertTrue(client.interpret(888888, "订单已经发货").success());
    }

    @Test
    @DisplayName("10060001「支付单不存在」→ 失败（时序竞态，交上层重试，不能误判成功）")
    void failOnOrderNotReady() {
        UploadResult r = client.interpret(10060001, "支付单不存在");
        assertFalse(r.success());
        assertEquals(10060001, r.errcode());
    }

    @Test
    @DisplayName("其它真实错误 → 失败并带回 errcode/errmsg")
    void failOnGenericError() {
        UploadResult r = client.interpret(40003, "invalid openid");
        assertFalse(r.success());
        assertEquals(40003, r.errcode());
        assertEquals("invalid openid", r.errmsg());
    }
}
