package org.dromara.gz.common.pay.shipping.impl;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import org.dromara.gz.common.pay.shipping.ShippingInfo;
import org.dromara.gz.common.pay.shipping.ShippingPackage;
import org.dromara.gz.common.pay.shipping.WxShippingClient.UploadCommand;
import org.dromara.gz.common.pay.shipping.WxShippingClient.UploadResult;
import org.dromara.gz.common.wechat.WxDeliveryListResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    private final WxRealShippingClient client = new WxRealShippingClient(null, new WxDeliveryListResolver(null));

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

    // ============================================================
    //  请求体形状（GZ-JP-301 加实物多包裹后补）
    // ============================================================

    private static ShippingPackage pkg(String tracking, String company, String desc, String contact) {
        ShippingPackage p = new ShippingPackage(tracking, "sf", "顺丰速运", desc, contact);
        p.setExpressCompany(company);
        return p;
    }

    @Test
    @DisplayName("★ 回归：虚拟商品（拼豆）请求体与加实物之前逐字一致")
    void virtualBodyUnchanged() {
        // 这是线上拼豆走的那条路 —— 多包裹改造绝不能动它的报文形状
        UploadCommand cmd = new UploadCommand("4200TX", "openid-x", ShippingInfo.VIRTUAL, "拼豆预约");
        JSONObject body = client.buildBody(cmd);

        assertEquals(3, body.getInt("logistics_type"));
        assertEquals(ShippingInfo.DELIVERY_MODE_UNIFIED, body.getInt("delivery_mode"));
        assertFalse(body.containsKey("is_all_delivered"), "★ 统一发货绝不能带 is_all_delivered");

        JSONArray list = body.getJSONArray("shipping_list");
        assertEquals(1, list.size());
        JSONObject only = list.getJSONObject(0);
        assertEquals("拼豆预约", only.getStr("item_desc"));
        assertEquals(1, only.size(), "★ 虚拟商品那条只该有 item_desc，不能多出运单号/快递/联系人");

        // order_number_type = 2 表示「用微信支付单号」（数字，不是字符串 —— 传字符串会被微信拒）
        assertEquals(2, body.getJSONObject("order_key").getInt("order_number_type"));
        assertEquals("4200TX", body.getJSONObject("order_key").getStr("transaction_id"));
        assertEquals("openid-x", body.getJSONObject("payer").getStr("openid"));
    }

    @Test
    @DisplayName("★★ 实物分拆发货：每个包裹带运单号 / 快递编码 / 收件人掩码，且带 is_all_delivered")
    void physicalSplitBody() {
        UploadCommand cmd = new UploadCommand("4200TX", "openid-x", ShippingInfo.PHYSICAL, "整单",
            ShippingInfo.DELIVERY_MODE_SPLIT, false, "mp-applet-gz-jp",
            List.of(pkg("SF001", "SF", "立牌", "138****5678"),
                pkg("SF002", "SF", "吧唧 等 2 件", "138****5678")));

        JSONObject body = client.buildBody(cmd);

        assertEquals(1, body.getInt("logistics_type"), "实体物流必须是 1");
        assertEquals(ShippingInfo.DELIVERY_MODE_SPLIT, body.getInt("delivery_mode"));
        assertEquals(false, body.getBool("is_all_delivered"), "★ 分拆发货必填，否则 10060007");

        JSONArray list = body.getJSONArray("shipping_list");
        assertEquals(2, list.size(), "两个包裹要一起报（微信按整份清单覆盖）");
        JSONObject first = list.getJSONObject(0);
        assertEquals("SF001", first.getStr("tracking_no"));
        assertEquals("SF", first.getStr("express_company"));
        assertEquals("立牌", first.getStr("item_desc"));
        assertEquals("138****5678", first.getJSONObject("contact").getStr("receiver_contact"));
    }

    @Test
    @DisplayName("is_all_delivered 传 null 视为 false —— 错报 true 会让微信提前收口")
    void nullAllDeliveredMeansFalse() {
        UploadCommand cmd = new UploadCommand("4200TX", "openid-x", ShippingInfo.PHYSICAL, "整单",
            ShippingInfo.DELIVERY_MODE_SPLIT, null, "mp-applet-gz-jp",
            List.of(pkg("SF001", "SF", "立牌", "138****5678")));
        assertEquals(false, client.buildBody(cmd).getBool("is_all_delivered"));
    }

    // ============================================================
    //  上报前本地校验（不触网，拦在浪费一次调用之前）
    // ============================================================

    @Test
    @DisplayName("虚拟商品不做实物校验（没有包裹也合法）")
    void validateSkipsVirtual() {
        assertNull(client.validate(new UploadCommand("4200TX", "o", ShippingInfo.VIRTUAL, "拼豆预约")));
    }

    @Test
    @DisplayName("★ 实物缺运单号 / 缺快递编码 / 缺收件人联系方式 → 本地就拒，且原因是人话")
    void validateRejectsIncompletePhysical() {
        UploadResult noPkg = client.validate(new UploadCommand("4200TX", "o", ShippingInfo.PHYSICAL, "整单",
            ShippingInfo.DELIVERY_MODE_SPLIT, false, "c", List.of()));
        assertFalse(noPkg.success());
        assertTrue(noPkg.errmsg().contains("shipping_list"), "原因要指得出是哪个字段：" + noPkg.errmsg());

        UploadResult noCompany = client.validate(new UploadCommand("4200TX", "o", ShippingInfo.PHYSICAL, "整单",
            ShippingInfo.DELIVERY_MODE_SPLIT, false, "c",
            List.of(pkg("SF001", null, "立牌", "138****5678"))));
        assertFalse(noCompany.success());
        assertTrue(noCompany.errmsg().contains("顺丰速运"),
            "查不到编码时要点名是哪家快递，店员才知道换哪个：" + noCompany.errmsg());

        UploadResult noContact = client.validate(new UploadCommand("4200TX", "o", ShippingInfo.PHYSICAL, "整单",
            ShippingInfo.DELIVERY_MODE_SPLIT, false, "c",
            List.of(pkg("SF001", "SF", "立牌", null))));
        assertFalse(noContact.success());
        assertTrue(noContact.errmsg().contains("receiver_contact"), noContact.errmsg());
    }

    @Test
    @DisplayName("★ 包裹数超微信上限 15 → 本地拒，别白白烧一次调用（10060024）")
    void validateRejectsOverMaxPackages() {
        List<ShippingPackage> many = new ArrayList<>();
        for (int i = 0; i <= ShippingInfo.MAX_PACKAGES; i++) {
            many.add(pkg("SF" + i, "SF", "货", "138****5678"));
        }
        UploadResult r = client.validate(new UploadCommand("4200TX", "o", ShippingInfo.PHYSICAL, "整单",
            ShippingInfo.DELIVERY_MODE_SPLIT, false, "c", many));
        assertFalse(r.success());
        assertTrue(r.errmsg().contains("15"), r.errmsg());
    }
}
