package org.dromara.gz.common.pay.service.internal;

/**
 * 微信支付 V3 通道客户端抽象（GZ-PAY-001 AC 9，决策 D2）。
 *
 * <p>商户号下证前后无缝切换的核心：</p>
 * <ul>
 *   <li>{@link WechatPayV3ClientImpl} — 官方 SDK wechatpay-java 真实实现（商户号到位后 staging/prod 用，gz.pay.client.mode=real）</li>
 *   <li>{@link MockWechatPayClient} — mock 实现（返回固定 prepay_id + 模拟回调；dev 默认 + 单测永远用，gz.pay.client.mode=mock）</li>
 * </ul>
 *
 * <p>service 层只依赖本接口，不直接 import 官方 SDK 类型 —— 避免 mock profile 下 SDK 因缺真实证书初始化失败。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-PAY-001)
 */
public interface IWechatPayClient {

    /**
     * JSAPI 统一下单（doc/10 §2.N2 → N3）。
     *
     * @param req 下单请求（out_trade_no / amount / openid / description）
     * @return prepay_id（mock 返回固定值；real 调微信 /v3/pay/transactions/jsapi 拿）
     */
    String createJsapiOrder(UnifiedOrderRequest req);

    /**
     * 用 prepay_id 计算 mp 端 {@code uni.requestPayment} 的 5 参签名（doc/10 §2.N3）。
     *
     * <p>real 实现用商户私钥 RSA-SHA256 签 {@code appId\ntimeStamp\nnonceStr\nprepay_id={id}\n}；
     * mock 返回固定占位签名（mp dev 端只验流程不验真签）。</p>
     *
     * @param prepayId 统一下单返回的 prepay_id
     * @return 5 参签名（timeStamp / nonceStr / package / signType / paySign）
     */
    JsapiPayParams buildPayParams(String prepayId);

    /**
     * 解析 + 验签微信回调（doc/10 §2.N6 → N7 / AC 6）。
     *
     * <p>real 用 SDK {@code NotificationParser.parse} 验 Wechatpay-Signature 头 + AES-GCM 解密 body；
     * 验签失败 throw {@link WechatPayVerifyException}。mock 直接把 body 当已解密 JSON 返回（不验签）。</p>
     *
     * @param ctx 回调原始 HTTP 上下文（headers + body）
     * @return 解密后的回调结果（transaction_id / out_trade_no / trade_state / amount.payer_total 等）
     * @throws WechatPayVerifyException 验签失败
     */
    CallbackResult parseAndVerifyNotify(NotifyContext ctx);

    /**
     * 统一下单请求。
     */
    record UnifiedOrderRequest(String outTradeNo, long amountCent, String openid, String description) {
    }

    /**
     * mp 端 5 参签名。
     */
    record JsapiPayParams(String timeStamp, String nonceStr, String packageVal, String signType, String paySign) {
    }

    /**
     * 回调原始 HTTP 上下文（验签所需的头 + body）。
     */
    record NotifyContext(String timestamp, String nonce, String signature, String serial, String body) {
    }

    /**
     * 回调解析结果（验签 + 解密后的关键业务字段）。
     *
     * @param transactionId 微信交易号
     * @param outTradeNo    业务订单号
     * @param tradeState    交易状态（SUCCESS / ...）
     * @param payerTotal    用户实际支付金额（分）
     * @param feeCent       通道手续费（分，回调暂不直接给，real 实现填 null，对账期单独拉）
     * @param decryptedBody 解密后的完整 JSON（入 callback_log raw_body）
     */
    record CallbackResult(String transactionId, String outTradeNo, String tradeState,
                          Long payerTotal, Long feeCent, String decryptedBody) {
    }
}
