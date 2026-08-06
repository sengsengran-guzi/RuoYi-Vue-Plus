package org.dromara.gz.common.pay.shipping.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.gz.common.pay.shipping.WxShippingClient;
import org.dromara.gz.common.wechat.WxAccessTokenManager;
import org.dromara.gz.common.wechat.WxAdapterDispatcher;
import org.dromara.gz.common.wechat.impl.WxRealAccessTokenManager;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * 微信发货信息录入 real 实现（upload_shipping_info）。
 *
 * <p><b>装配</b>（ADR-0019 §1）：无条件注册，与 {@link WxMockShippingClient} 同时在容器里，由
 * {@link WxAdapterDispatcher} 按当前 clientid 对应小程序的 mode 运行时选择。调用链：
 * access_token（{@link WxRealAccessTokenManager} Redis 缓存）→ POST upload_shipping_info。</p>
 *
 * <p><b>依赖具体的 {@link WxRealAccessTokenManager} 而非 {@link WxAccessTokenManager} 接口</b>：
 * 接口的 {@code @Primary} 实现是 dispatcher，注入它会构成 dispatcher → 本类 → dispatcher 的构造器
 * 循环依赖（Spring Boot 3 默认禁止）。</p>
 *
 * <p><b>⚠️ 上报是 {@code @Async} 执行（无请求上下文）</b>：clientid 回落到
 * {@code wx.miniapp.default-client-id}，即当前只按默认小程序取 token / 选通道。多小程序都要真上报时，
 * 发货任务需带上自己的 clientid（见 GZ-SYS-021 / GZ-SYS-022）。</p>
 *
 * <p><b>容错</b>：本类不抛异常 —— 网络/微信错误落 {@link UploadResult#fail}；access_token 失效
 * （40001/42001/40014）强刷一次重试。</p>
 *
 * <p><b>幂等成功判定</b>（{@link #interpret}）：微信侧已登记发货即视为成功、不再重试 ——
 * {@code 268440065}「订单已发货」、{@code 10060023}「已上传该订单物流信息（发货信息未更新）」、
 * 或 errmsg 含「已发货」。此判定是 74 单历史卡单的根因修复：过去 {@code 10060023} 被误判为失败，
 * 即便店家已在小程序订单中心手动发货、重试仍永远回写 {@code failed}，状态永不收敛。</p>
 *
 * <p><b>时序竞态</b>：支付回调后即时上报常撞 {@code 10060001}「支付单不存在」（微信订单索引尚未就绪），
 * 归类为<b>可重试失败</b>，由 {@code GzPayShippingServiceImpl#tryUploadAsync} 短延迟重试自愈。</p>
 *
 * @author kevin-coder (sensenran-guzi)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WxRealShippingClient implements WxShippingClient {

    /** 发货信息录入接口。 */
    private static final String UPLOAD_URL = "https://api.weixin.qq.com/wxa/sec/order/upload_shipping_info";

    /** HTTP 超时（毫秒）。 */
    private static final int HTTP_TIMEOUT_MS = 5000;

    /** item_desc 最大长度（微信限制 ~128，留余量截断）。 */
    private static final int ITEM_DESC_MAX_LEN = 120;

    /** 统一发货（不分拆）。 */
    private static final int DELIVERY_MODE_ALL = 1;

    /** order_number_type=2 → 用微信支付单号 transaction_id 定位订单。 */
    private static final int ORDER_NUMBER_TYPE_TRANSACTION_ID = 2;

    /** access_token 失效错误码 → 强刷重试。 */
    private static final int ERR_TOKEN_INVALID = 40001;
    private static final int ERR_TOKEN_EXPIRED = 42001;
    private static final int ERR_TOKEN_BAD = 40014;

    /** 「订单已发货」幂等错误码（微信重复发货保护）。 */
    private static final int ERR_ALREADY_SHIPPED = 268440065;

    /**
     * 「已上传该订单的物流信息」幂等错误码（errmsg 为「发货信息未更新」，措辞误导）。
     * 订单已被（手动或本服务）登记发货后重复上报即返此码 —— 视为成功，避免历史卡单永卡 failed。
     */
    private static final int ERR_SHIPPING_NOT_UPDATED = 10060023;

    /** 「支付单不存在」—— 支付后微信订单索引尚未就绪的时序竞态，可重试（非终态失败）。 */
    private static final int ERR_ORDER_NOT_READY = 10060001;

    private final WxRealAccessTokenManager accessTokenManager;

    @Override
    public UploadResult uploadShippingInfo(UploadCommand cmd) {
        JSONObject resp = post(cmd, accessTokenManager.getToken(false));
        Integer errcode = resp.getInt("errcode");
        // token 失效 → 强刷一次重试
        if (errcode != null && (errcode == ERR_TOKEN_INVALID || errcode == ERR_TOKEN_EXPIRED || errcode == ERR_TOKEN_BAD)) {
            log.warn("[wx-shipping] access_token 失效 errcode={}，强刷重试 transaction_id={}", errcode, cmd.transactionId());
            resp = post(cmd, accessTokenManager.getToken(true));
            errcode = resp.getInt("errcode");
        }
        String errmsg = resp.getStr("errmsg");
        UploadResult result = interpret(errcode, errmsg);
        if (result.success()) {
            log.info("[wx-shipping] 发货信息上报成功（含幂等）transaction_id={} errcode={}", cmd.transactionId(), errcode);
        } else if (errcode != null && errcode == ERR_ORDER_NOT_READY) {
            log.warn("[wx-shipping] 微信订单索引未就绪，稍后重试 transaction_id={} errcode={}", cmd.transactionId(), errcode);
        } else {
            log.warn("[wx-shipping] 发货信息上报失败 transaction_id={} errcode={} errmsg={}", cmd.transactionId(), errcode, errmsg);
        }
        return result;
    }

    /**
     * 把微信响应错误码/描述归类为成功 / 失败。抽成纯函数便于单测（不触网）。
     *
     * <p>成功：{@code errcode==0} 或 null（正常）；{@code 268440065} / {@code 10060023}（微信侧已登记发货，
     * 幂等）；errmsg 含「已发货」。其余（含 {@code 10060001} 时序竞态）→ 失败，交上层重试。</p>
     *
     * @param errcode 微信错误码（可能为 null = 成功）
     * @param errmsg  微信错误描述
     * @return 上报结果
     */
    UploadResult interpret(Integer errcode, String errmsg) {
        if (errcode == null || errcode == 0) {
            return UploadResult.ok();
        }
        if (errcode == ERR_ALREADY_SHIPPED || errcode == ERR_SHIPPING_NOT_UPDATED
            || (errmsg != null && (errmsg.contains("已发货") || errmsg.contains("已经发货")))) {
            return UploadResult.ok();
        }
        return UploadResult.fail(errcode, errmsg);
    }

    /** 调 upload_shipping_info，返回原始响应 JSON（错误码留给调用方判定，便于 token 重试）。 */
    private JSONObject post(UploadCommand cmd, String accessToken) {
        String url = UPLOAD_URL + "?access_token=" + accessToken;
        String reqBody = buildBody(cmd).toString();
        String body;
        try {
            body = HttpUtil.createPost(url)
                .header("Content-Type", "application/json;charset=UTF-8")
                .body(reqBody)
                .timeout(HTTP_TIMEOUT_MS)
                .execute()
                .body();
        } catch (Exception e) {
            log.error("[wx-shipping] upload_shipping_info 网络异常 transaction_id={}", cmd.transactionId(), e);
            // 网络异常归一为可重试失败码（非微信码，自定义 -1）
            return JSONUtil.createObj().set("errcode", -1).set("errmsg", "网络异常: " + e.getMessage());
        }
        log.debug("[wx-shipping] upload_shipping_info resp={}", body);
        return JSONUtil.parseObj(body);
    }

    /** 构造 upload_shipping_info 请求体（虚拟商品：shipping_list 仅 item_desc，无运单号/快递公司）。 */
    private JSONObject buildBody(UploadCommand cmd) {
        String desc = cmd.itemDesc() == null ? "" : cmd.itemDesc();
        if (desc.length() > ITEM_DESC_MAX_LEN) {
            desc = desc.substring(0, ITEM_DESC_MAX_LEN);
        }
        JSONObject orderKey = JSONUtil.createObj()
            .set("order_number_type", ORDER_NUMBER_TYPE_TRANSACTION_ID)
            .set("transaction_id", cmd.transactionId());
        JSONArray shippingList = new JSONArray();
        shippingList.add(JSONUtil.createObj().set("item_desc", desc));
        String uploadTime = OffsetDateTime.now(ZoneOffset.ofHours(8)).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        return JSONUtil.createObj()
            .set("order_key", orderKey)
            .set("logistics_type", cmd.logisticsType())
            .set("delivery_mode", DELIVERY_MODE_ALL)
            .set("shipping_list", shippingList)
            .set("upload_time", uploadTime)
            .set("payer", JSONUtil.createObj().set("openid", cmd.openid()));
    }
}
