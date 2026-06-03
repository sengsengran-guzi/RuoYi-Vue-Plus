package org.dromara.gz.common.pay.service.internal;

import com.wechat.pay.java.core.Config;
import com.wechat.pay.java.core.RSAAutoCertificateConfig;
import com.wechat.pay.java.core.exception.ValidationException;
import com.wechat.pay.java.core.notification.NotificationConfig;
import com.wechat.pay.java.core.notification.NotificationParser;
import com.wechat.pay.java.core.notification.RequestParam;
import com.wechat.pay.java.service.payments.jsapi.JsapiServiceExtension;
import com.wechat.pay.java.service.payments.jsapi.model.Amount;
import com.wechat.pay.java.service.payments.jsapi.model.CloseOrderRequest;
import com.wechat.pay.java.service.payments.jsapi.model.Payer;
import com.wechat.pay.java.service.payments.jsapi.model.PrepayRequest;
import com.wechat.pay.java.service.payments.jsapi.model.PrepayWithRequestPaymentResponse;
import com.wechat.pay.java.service.payments.jsapi.model.QueryOrderByOutTradeNoRequest;
import com.wechat.pay.java.service.payments.model.Transaction;
import com.wechat.pay.java.service.refund.RefundService;
import com.wechat.pay.java.service.refund.model.AmountReq;
import com.wechat.pay.java.service.refund.model.CreateRequest;
import com.wechat.pay.java.service.refund.model.Refund;
import com.wechat.pay.java.service.refund.model.RefundNotification;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.gz.common.pay.config.WechatPayProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

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
    private RefundService refundService;
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
        this.refundService = new RefundService.Builder().config(config).build();
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

    @Override
    public QueryResult queryByOutTradeNo(String outTradeNo) {
        QueryOrderByOutTradeNoRequest request = new QueryOrderByOutTradeNoRequest();
        request.setMchid(props.getMchId());
        request.setOutTradeNo(outTradeNo);
        Transaction tx = jsapiService.queryOrderByOutTradeNo(request);
        Long payerTotal = tx.getAmount() != null && tx.getAmount().getPayerTotal() != null
            ? tx.getAmount().getPayerTotal().longValue() : null;
        String tradeState = tx.getTradeState() != null ? tx.getTradeState().name() : null;
        log.info("[gz-pay] queryByOutTradeNo out_trade_no={} → trade_state={}", outTradeNo, tradeState);
        // fee_cent 留 null（查单不含通道费，由 PAY-104 拉 fundflowbill 回写）；rawBody 用 SDK toString 入审计
        return new QueryResult(tx.getOutTradeNo(), tx.getTransactionId(), tradeState, payerTotal, null, tx.toString());
    }

    @Override
    public void closeOrder(String outTradeNo) {
        CloseOrderRequest request = new CloseOrderRequest();
        request.setMchid(props.getMchId());
        request.setOutTradeNo(outTradeNo);
        jsapiService.closeOrder(request);
        log.info("[gz-pay] closeOrder out_trade_no={}（微信侧关单成功）", outTradeNo);
    }

    /**
     * 拉取资金账单（GZ-PAY-104，doc/11 F4.3 / F9.2）。
     *
     * <p><b>真实流程</b>（商户号下证后实现）：① 用 SDK 调
     * {@code GET /v3/bill/fundflowbill?bill_date=yyyy-MM-dd&account_type=BASIC}（自动签名 + 验签平台证书）
     * 拿响应 {@code download_url} + {@code hash_type=SHA1} + {@code hash_value} → ② 用同一商户认证 GET
     * {@code download_url} 取 gzip 二进制 → ③ GZIPInputStream 解压成 UTF-8 CSV 文本 → ④ 校验下载文件 sha1
     * = {@code hash_value}（service 也会复校，AC3）→ 返回 {@link FundFlowBill}。</p>
     *
     * <p><b>降级现状</b>（外部前置风险，ticket §备注 / D08 README R1）：商户号 + cert + apiV3Key 未到位，
     * 真实账单格式（列序 / 手续费列名）以官方文档为准但<b>未做真实端到端验证</b>。本卡 DoD 仅认 mock 全链路；
     * real 路径在 buffer 期商户配置就绪后联调补打。当前 real 模式直接 fail-fast 抛错，避免 prod 半实现裸奔
     * 灌脏 fee_cent。</p>
     *
     * @param billDate 账单业务日
     * @return 资金账单（CSV + sha1）
     */
    @Override
    public FundFlowBill downloadFundFlowBill(LocalDate billDate) {
        // 真实端到端联调依赖甲方商户配置（外部前置风险）。real 模式未联调前 fail-fast，
        // 不返回半实现结果污染 fee_cent 回写（CLAUDE.md §6 #7 不吞异常 / 不灌脏数据）。
        throw new ServiceException(
            "WechatPayV3ClientImpl.downloadFundFlowBill 待商户号下证后联调（GZ-PAY-104 外部前置风险）。" +
            "联调步骤见本方法 javadoc；当前请用 gz.pay.client-mode=mock 跑 mock 全链路自测。bill_date=" + billDate);
    }

    // ============================================================
    //  GZ-PAY-103 退款 / 退款回调（real 实现）
    // ============================================================

    @Override
    public RefundResult refund(RefundRequest req) {
        CreateRequest request = new CreateRequest();
        request.setOutTradeNo(req.outTradeNo());
        request.setOutRefundNo(req.outRefundNo());
        request.setReason(req.reason());
        request.setNotifyUrl(req.notifyUrl());

        AmountReq amount = new AmountReq();
        amount.setRefund(req.refundAmountCent());
        amount.setTotal(req.totalAmountCent());
        amount.setCurrency("CNY");
        request.setAmount(amount);

        // SDK 同步提交退款申请；受理失败抛 SDK 运行时异常 → service 捕获回滚 paid（doc/10 §6.E4）
        Refund refund = refundService.create(request);
        String status = refund.getStatus() != null ? refund.getStatus().name() : null;
        log.info("[gz-pay] refund out_trade_no={} out_refund_no={} → refund_id={} status={}",
            req.outTradeNo(), req.outRefundNo(), refund.getRefundId(), status);
        return new RefundResult(refund.getRefundId(), status, refund.toString());
    }

    @Override
    public RefundCallbackResult parseAndVerifyRefundNotify(NotifyContext ctx) {
        RequestParam requestParam = new RequestParam.Builder()
            .serialNumber(ctx.serial())
            .nonce(ctx.nonce())
            .signature(ctx.signature())
            .timestamp(ctx.timestamp())
            .body(ctx.body())
            .build();
        try {
            // 复用同一套 SDK NotificationParser AES-GCM 验签 + 解密（强约束 #2），解析为退款回调模型
            RefundNotification rn = notificationParser.parse(requestParam, RefundNotification.class);
            String refundStatus = rn.getRefundStatus() != null ? rn.getRefundStatus().name() : null;
            return new RefundCallbackResult(
                rn.getRefundId(),
                rn.getOutRefundNo(),
                rn.getOutTradeNo(),
                refundStatus,
                rn.toString());
        } catch (ValidationException e) {
            throw new WechatPayVerifyException("微信退款回调验签失败: " + e.getMessage(), e);
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
