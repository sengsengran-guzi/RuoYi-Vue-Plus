package org.dromara.gz.common.pay.service.internal;

import com.wechat.pay.java.core.Config;
import com.wechat.pay.java.core.RSAPublicKeyConfig;
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
import org.dromara.gz.common.wechat.WxMiniappProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * 微信支付 V3 通道真实实现（GZ-PAY-001 AC 9，官方 SDK wechatpay-java，决策 D1）。
 *
 * <p><b>何时生效</b>：{@code gz.pay.client.mode=real}（staging/prod）。
 * dev / 单测走 {@link MockWechatPayClient}，本类不被实例化 —— 故真实凭证未配时不会启动报错。</p>
 *
 * <p><b>加密模式</b>：商户（1731037015 成都谷子宇宙贸易有限公司）为微信支付<b>公钥模式</b>，
 * 用 {@link RSAPublicKeyConfig}（非平台证书自动下载模式）。需注入 6 项：mchId / apiV3Key /
 * privateKeyPath / mchCertSerial / publicKeyPath（微信支付公钥 pem）/ publicKeyId（PUB_KEY_ID_*）。
 * 机密项（apiV3Key / 私钥）走 env var 不入 git（强约束 #4）。</p>
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
     * 初始化 SDK（用 {@link RSAPublicKeyConfig} 微信支付公钥模式：响应验签用微信支付公钥，不下载平台证书）。
     *
     * <p>real profile 启动时构建；缺失关键配置直接 fail-fast（避免 prod 半初始化裸奔）。</p>
     */
    @PostConstruct
    public void init() {
        if (props.getMchId() == null || props.getApiV3Key() == null || props.getPrivateKeyPath() == null
            || props.getPublicKeyPath() == null || props.getPublicKeyId() == null) {
            throw new IllegalStateException(
                "gz.pay.client-mode=real 但商户配置不全（mchId / apiV3Key / privateKeyPath / publicKeyPath / publicKeyId），" +
                "请注入 env var（WECHAT_PAY_MCH_ID / WECHAT_PAY_API_V3_KEY / WECHAT_PAY_PRIVATE_KEY_PATH / " +
                "WECHAT_PAY_PUBLIC_KEY_PATH / WECHAT_PAY_PUBLIC_KEY_ID）");
        }
        this.config = new RSAPublicKeyConfig.Builder()
            .merchantId(props.getMchId())
            .privateKeyFromPath(props.getPrivateKeyPath())
            .publicKeyFromPath(props.getPublicKeyPath())
            .publicKeyId(props.getPublicKeyId())
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
        String appid = requireRealAppid(req.appid(), "统一下单");
        PrepayRequest request = new PrepayRequest();
        // ADR-0019 §3：appid 来自下单上下文（PayAppidResolver），不再读全局 gz.pay.appid ——
        // prepay_id 与 appid 绑定，新小程序拿旧 appid 的 prepay_id 调起必失败。
        request.setAppid(appid);
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
        log.info("[gz-pay] createJsapiOrder out_trade_no={} appid={} → prepay_id={}",
            req.outTradeNo(), appid, prepayId);
        return prepayId;
    }

    @Override
    public JsapiPayParams buildPayParams(String prepayId, String appid) {
        // SDK prepayWithRequestPayment 已能直接出 5 参，但本接口契约按 prepay_id 二次构建，
        // 为复用「一次下单一次签名」，real 路径实际由 createJsapiOrder 返回的 prepay_id 经此重签。
        // 简化：用 SDK 同款字段拼装 —— 真实联调期（buffer）若需严格一致，改 createJsapiOrder 直接缓存 5 参。
        //
        // ★ ADR-0019 §3：签名第一因子必须是「实际调起方那个小程序的 appid」，由调用方从下单上下文传入。
        //   这里与 createJsapiOrder 用同一个 appid —— 两处不一致 = 微信验签必挂（且下单接口仍返 200）。
        String signAppid = requireRealAppid(appid, "调起支付签名");
        long ts = System.currentTimeMillis() / 1000;
        String nonce = java.util.UUID.randomUUID().toString().replace("-", "");
        String pkg = "prepay_id=" + prepayId;
        String message = signAppid + "\n" + ts + "\n" + nonce + "\n" + pkg + "\n";
        String paySign = signWithMerchantPrivateKey(message);
        log.info("[gz-pay] buildPayParams prepay_id={} appid={}", prepayId, signAppid);
        return new JsapiPayParams(String.valueOf(ts), nonce, pkg, "RSA", paySign);
    }

    /**
     * 校验下单上下文给的 appid 是真实可用的小程序 appid（real 通道专用）。
     *
     * <p>拦三类值：null / 空白（调用方忘了传）、{@code wxMOCK}（该小程序配的是 mock 通道，
     * 却走了 real 收款）、建表 seed 占位值 {@code wx_placeholder_appid} / {@code wx_dev_placeholder}
     * （配置没回补）。这三种情况若放行，表现是微信返「appid 不存在 / 与 mch_id 未绑定 / 验签失败」等
     * 含糊错误，排查成本远高于此处直接报出根因。</p>
     */
    private String requireRealAppid(String appid, String scene) {
        if (appid == null || appid.isBlank()
            || WxMiniappProperties.MOCK_APPID.equalsIgnoreCase(appid)
            || PayAppidResolver.isPlaceholderAppid(appid)) {
            throw new ServiceException("微信支付[" + scene + "]拿到的 appid 不可用（值=" + appid
                + "）。real 通道要求下单上下文给出真实小程序 appid：请检查 gz_pay_channel.appid（按 clientid 一行）"
                + " 或 wx.miniapp.apps.<clientid>.appid 是否已回补（ADR-0019 §3）");
        }
        return appid;
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

    // ============================================================
    //  GZ-PAY-105 反向打款（商家转账到零钱，real hook，ADR-0006）
    // ============================================================

    @Override
    public TransferResult transferToUserWallet(TransferRequest req) {
        // 待 transferbatch SDK 批准（ADR-0006 / 铁律 8）。
        // 商户「商家转账到零钱」权限需在微信支付商户平台单独申请（非默认开通，有资质门槛，ADR-0006 Consequences），
        // 且 com.wechat.pay.java.service.transferbatch 子包依赖待 Kevin 批准 —— 在此之前 real 不接通道、不私引 SDK。
        // 权限 + 依赖到位后此处接 V3 POST /v3/transfer/batches（TransferBatchService.initiateBatchTransfer），
        // 切 gz.pay.client.mode=real 即生效，service 层零改动（同 ADR-0003 范式）。
        throw new UnsupportedOperationException(
            "real 商家转账未启用：商户「商家转账到零钱」权限 + transferbatch SDK 待批准（ADR-0006 / 铁律 8）；当前请用 gz.pay.client.mode=mock");
    }

    @Override
    public TransferQueryResult queryTransferByOutNo(String outPayoutNo) {
        // 待 transferbatch SDK 批准（ADR-0006 / 铁律 8）。
        // 权限 + 依赖到位后接 V3 GET /v3/transfer/batches/out-batch-no/{out_batch_no}（TransferBatchService.getTransferBatchByOutNo）。
        throw new UnsupportedOperationException(
            "real 商家转账查单未启用：待商户权限 + transferbatch SDK 批准（ADR-0006 / 铁律 8）；当前请用 gz.pay.client.mode=mock");
    }
}
