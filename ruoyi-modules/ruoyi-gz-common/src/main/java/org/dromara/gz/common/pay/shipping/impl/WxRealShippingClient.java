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
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * 微信发货信息录入 real 实现（upload_shipping_info）。
 *
 * <p>启用条件：{@code wx.miniapp.appid} 为非 wxMOCK 真实 AppID（与登录 / 手机号 real 通道互斥唯一）。
 * 调用链：access_token（{@link WxAccessTokenManager} 全局缓存）→ POST upload_shipping_info。</p>
 *
 * <p><b>容错</b>：本类不抛异常 —— 网络/微信错误落 {@link UploadResult#fail}；access_token 失效
 * （40001/42001/40014）强刷一次重试；微信判「已发货」（268440065 / errmsg 含「已发货」）视为幂等成功。</p>
 *
 * @author kevin-coder (sensenran-guzi)
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnExpression("'${wx.miniapp.appid:wxMOCK}' != 'wxMOCK'")
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

    private final WxAccessTokenManager accessTokenManager;

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
        if (errcode == null || errcode == 0) {
            log.info("[wx-shipping] 发货信息上报成功 transaction_id={}", cmd.transactionId());
            return UploadResult.ok();
        }
        // 微信侧已登记发货 → 幂等成功（避免无意义重试）
        if (errcode == ERR_ALREADY_SHIPPED || (errmsg != null && (errmsg.contains("已发货") || errmsg.contains("已经发货")))) {
            log.info("[wx-shipping] 发货信息已存在视为成功 transaction_id={} errcode={}", cmd.transactionId(), errcode);
            return UploadResult.ok();
        }
        log.warn("[wx-shipping] 发货信息上报失败 transaction_id={} errcode={} errmsg={}", cmd.transactionId(), errcode, errmsg);
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
