package org.dromara.gz.common.pay.service.internal;

import com.wechat.pay.java.core.Config;
import com.wechat.pay.java.core.RSAAutoCertificateConfig;
import com.wechat.pay.java.core.exception.ValidationException;
import com.wechat.pay.java.core.notification.NotificationConfig;
import com.wechat.pay.java.core.notification.NotificationParser;
import com.wechat.pay.java.core.notification.RequestParam;
import com.wechat.pay.java.service.payments.jsapi.JsapiServiceExtension;
import com.wechat.pay.java.service.payments.jsapi.model.Amount;
import com.wechat.pay.java.service.payments.jsapi.model.Payer;
import com.wechat.pay.java.service.payments.jsapi.model.PrepayRequest;
import com.wechat.pay.java.service.payments.jsapi.model.PrepayWithRequestPaymentResponse;
import com.wechat.pay.java.service.payments.model.Transaction;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.gz.common.pay.config.WechatPayProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 微信支付 V3 通道真实实现（GZ-PAY-001 AC 9，官方 SDK wechatpay-java，决策 D1）。
 *
 * <p><b>何时生效</b>：{@code gz.pay.client.mode=real}（商户号下证后 staging/prod）。
 * dev / 单测走 {@link MockWechatPayClient}，本类不被实例化 —— 故 SDK 真实证书未配时不会启动报错。</p>
 *
 * <p><b>降级现状（2026-06-01）</b>：商户号申请中（5/29 启动，5-10 工作日），未下证 →
 * 本类已实现但<b>未做真实端到端联调</b>。真实 0.01 元单在 buffer 期补打（ADR-0003）。
 * staging/prod 切 real profile 前需注入 env var（WECHAT_PAY_MCH_ID / API_V3_KEY / PRIVATE_KEY_PATH /
 * CERT_SERIAL / NOTIFY_URL）。</p>
 *
 * <p><b>验签</b>（AC 6）：{@link NotificationParser#parse} 验 Wechatpay-Signature 头 + AES-GCM
 * 解密，失败 throw SDK {@link ValidationException} → 本类转 {@link WechatPayVerifyException}
 * 让 controller 写 callback_log failed + 401（不静默）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-PAY-001)
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "gz.pay", name = "client-mode", havingValue = "real")
public class WechatPayV3ClientImpl implements IWechatPayClient {

    private final WechatPayProperties props;

    private Config config;
    private JsapiServiceExtension jsapiService;
    private NotificationParser notificationParser;

    /**
     * 初始化 SDK（用 RSAAutoCertificateConfig 自动下载/轮换微信平台证书）。
     *
     * <p>real profile 启动时构建；缺失关键配置直接 fail-fast（避免 prod 半初始化裸奔）。</p>
     */
    @PostConstruct
    public void init() {
        if (props.getMchId() == null || props.getApiV3Key() == null || props.getPrivateKeyPath() == null) {
            throw new IllegalStateException(
                "gz.pay.client-mode=real 但商户配置不全（mchId / apiV3Key / privateKeyPath），" +
                "请注入 env var（WECHAT_PAY_MCH_ID / WECHAT_PAY_API_V3_KEY / WECHAT_PAY_PRIVATE_KEY_PATH）");
        }
        this.config = new RSAAutoCertificateConfig.Builder()
            .merchantId(props.getMchId())
            .privateKeyFromPath(props.getPrivateKeyPath())
            .merchantSerialNumber(props.getMchCertSerial())
            .apiV3Key(props.getApiV3Key())
            .build();
        this.jsapiService = new JsapiServiceExtension.Builder().config(config).signType("RSA").build();
        this.notificationParser = new NotificationParser((NotificationConfig) config);
        log.info("[gz-pay] WechatPayV3ClientImpl 初始化完成（real，mch_id={}）", mask(props.getMchId()));
    }

    @Override
    public String createJsapiOrder(UnifiedOrderRequest req) {
        PrepayRequest request = new PrepayRequest();
        request.setAppid(props.getAppid());
        request.setMchid(props.getMchId());
        request.setDescription(req.description());
        request.setOutTradeNo(req.outTradeNo());
        request.setNotifyUrl(props.getNotifyUrl());

        Amount amount = new Amount();
        amount.setTotal((int) req.amountCent());
        amount.setCurrency("CNY");
        request.setAmount(amount);

        Payer payer = new Payer();
        payer.setOpenid(req.openid());
        request.setPayer(payer);

        // prepayWithRequestPayment 一步拿到 prepay_id 包装 + 5 参签名；此处仅取 prepay_id 落库
        PrepayWithRequestPaymentResponse resp = jsapiService.prepayWithRequestPayment(request);
        // packageVal 形如 "prepay_id=wx..."，剥前缀回填 prepay_id
        String pkg = resp.getPackageVal();
        String prepayId = pkg != null && pkg.startsWith("prepay_id=") ? pkg.substring("prepay_id=".length()) : pkg;
        log.info("[gz-pay] createJsapiOrder out_trade_no={} → prepay_id={}", req.outTradeNo(), prepayId);
        return prepayId;
    }

    @Override
    public JsapiPayParams buildPayParams(String prepayId) {
        // SDK prepayWithRequestPayment 已能直接出 5 参，但本接口契约按 prepay_id 二次构建，
        // 为复用「一次下单一次签名」，real 路径实际由 createJsapiOrder 返回的 prepay_id 经此重签。
        // 简化：用 SDK 同款字段拼装 —— 真实联调期（buffer）若需严格一致，改 createJsapiOrder 直接缓存 5 参。
        long ts = System.currentTimeMillis() / 1000;
        String nonce = java.util.UUID.randomUUID().toString().replace("-", "");
        String pkg = "prepay_id=" + prepayId;
        String message = props.getAppid() + "\n" + ts + "\n" + nonce + "\n" + pkg + "\n";
        String paySign = signWithMerchantPrivateKey(message);
        return new JsapiPayParams(String.valueOf(ts), nonce, pkg, "RSA", paySign);
    }

    @Override
    public CallbackResult parseAndVerifyNotify(NotifyContext ctx) {
        RequestParam requestParam = new RequestParam.Builder()
            .serialNumber(ctx.serial())
            .nonce(ctx.nonce())
            .signature(ctx.signature())
            .timestamp(ctx.timestamp())
            .body(ctx.body())
            .build();
        try {
            Transaction tx = notificationParser.parse(requestParam, Transaction.class);
            Long payerTotal = tx.getAmount() != null ? tx.getAmount().getPayerTotal().longValue() : null;
            return new CallbackResult(
                tx.getTransactionId(),
                tx.getOutTradeNo(),
                tx.getTradeState() != null ? tx.getTradeState().name() : null,
                payerTotal,
                null,
                tx.toString());
        } catch (ValidationException e) {
            // 验签失败 → 转业务异常，不静默（AC 6 / CLAUDE.md §6 #7）
            throw new WechatPayVerifyException("微信回调验签失败: " + e.getMessage(), e);
        }
    }

    /**
     * 用商户私钥对 message 做 RSA-SHA256 签名（buildPayParams 用）。
     *
     * <p>真实联调期由 createJsapiOrder 的 prepayWithRequestPayment 一步出签更稳；此处保留独立签名
     * 路径以满足 IWechatPayClient 接口契约（mock 同形）。</p>
     */
    private String signWithMerchantPrivateKey(String message) {
        try {
            java.security.Signature sign = java.security.Signature.getInstance("SHA256withRSA");
            sign.initSign(loadPrivateKey());
            sign.update(message.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.Base64.getEncoder().encodeToString(sign.sign());
        } catch (Exception e) {
            throw new WechatPayVerifyException("商户私钥签名失败: " + e.getMessage(), e);
        }
    }

    private java.security.PrivateKey loadPrivateKey() throws Exception {
        String keyContent = new String(java.nio.file.Files.readAllBytes(
            java.nio.file.Paths.get(props.getPrivateKeyPath())), java.nio.charset.StandardCharsets.UTF_8);
        keyContent = keyContent
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replaceAll("\\s", "");
        byte[] der = java.util.Base64.getDecoder().decode(keyContent);
        return java.security.KeyFactory.getInstance("RSA")
            .generatePrivate(new java.security.spec.PKCS8EncodedKeySpec(der));
    }

    private static String mask(String s) {
        if (s == null || s.length() < 6) {
            return "******";
        }
        return s.substring(0, 2) + "****" + s.substring(s.length() - 2);
    }
}
