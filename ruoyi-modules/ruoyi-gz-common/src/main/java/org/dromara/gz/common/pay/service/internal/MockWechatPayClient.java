package org.dromara.gz.common.pay.service.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.gz.common.pay.config.WechatPayProperties;
import org.dromara.gz.common.pay.service.internal.bill.FundFlowBillCsvBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.UUID;

/**
 * 微信支付通道 mock 实现（GZ-PAY-001 AC 9）。
 *
 * <p><b>何时生效</b>：{@code gz.pay.client.mode != real}（dev 默认 + 单测 + 商户号未到位的 staging）。
 * 通过 {@link ConditionalOnProperty}（matchIfMissing=true）保证缺省即 mock，不依赖真实证书。</p>
 *
 * <p><b>行为</b>：</p>
 * <ul>
 *   <li>{@link #createJsapiOrder} → 返回固定格式 mock prepay_id（不连微信）</li>
 *   <li>{@link #buildPayParams} → 返回固定占位 5 参（mp dev 端只验流程）</li>
 *   <li>{@link #parseAndVerifyNotify} → 不验签，直接把 body 当已解密 JSON 解析；
 *       验签失败场景由 {@link #buildMockCallbackBody} 构造的 body 是否合法触发</li>
 * </ul>
 *
 * <p>验签失败的单测路径：测试构造非法 body（或调用方注入 throw），不依赖此 mock 内部判断。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-PAY-001)
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "gz.pay", name = "client-mode", havingValue = "mock", matchIfMissing = true)
public class MockWechatPayClient implements IWechatPayClient {

    /** mock prepay_id 前缀（便于 admin / 日志辨识非真实通道） */
    public static final String MOCK_PREPAY_PREFIX = "mock_prepay_";

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 测试可注入的查单 trade_state（GZ-PAY-102 AC3）。默认 NOTPAY（未支付）—— 单测据此覆盖
     * SUCCESS / NOTPAY / USERPAYING / CLOSED / REVOKED / PAYERROR 各分支。
     */
    private String queryTradeState = "NOTPAY";

    /**
     * 测试可注入的查单 payer_total（GZ-PAY-102）。SUCCESS 场景下回填到 QueryResult.payerTotal。
     */
    private Long queryPayerTotal;

    /**
     * mock 关单调用计数（GZ-PAY-102 AC8 断言用：超 30min NOTPAY → closeOrder 被调）。
     */
    private int closeOrderCallCount;

    /**
     * 测试可注入的资金账单 CSV 覆盖（GZ-PAY-104 AC7）。非 null 时 {@link #downloadFundFlowBill} 直接返回它，
     * 由调用方（单测）构造含「正常匹配 / 孤儿 / 缺账」的可控账单。null 时返回内置默认 mock 账单。
     */
    private String overrideBillCsv;

    /**
     * 测试可注入的 hash 覆盖（GZ-PAY-104 AC3）。非 null 时 {@link #downloadFundFlowBill} 返回它作为微信声明
     * hash —— 与 CSV 真实 sha1 不一致即驱动 service 校验失败抛错路径。null 时返回 CSV 真实 sha1（匹配）。
     */
    private String overrideHashValue;

    /**
     * 测试可注入的退款受理失败开关（GZ-PAY-103 AC9）。true → {@link #refund} 抛异常模拟微信受理失败，
     * 驱动 service「受理失败 → gz_pay_refund=failed + transaction 回滚 paid」分支。默认 false（受理成功）。
     */
    private boolean refundAcceptFail = false;

    /** mock 反向打款单号前缀（GZ-PAY-105，便于 admin / 日志辨识非真实通道） */
    public static final String MOCK_PAYOUT_ID_PREFIX = "mock_payout_";
    /** mock 反向打款批次号前缀（GZ-PAY-105，幂等关键 batch_id 占位） */
    public static final String MOCK_BATCH_ID_PREFIX = "mock_batch_";

    /**
     * 测试可注入的反向打款查单态（GZ-PAY-105 AC2）。默认 PROCESSING（处理中）—— 单测据此覆盖
     * created→processing→success（注入 SUCCESS）与 failed 分支（注入 FAIL）。
     */
    private String queryTransferState = "PROCESSING";

    /**
     * 测试可注入的反向打款查单失败原因（GZ-PAY-105）。注入 FAIL 态时回填到 TransferQueryResult.failReason。
     */
    private String queryTransferFailReason = "余额不足（mock）";

    /**
     * 测试可注入的反向打款受理失败开关（GZ-PAY-105 AC2/AC8）。true → {@link #transferToUserWallet}
     * 抛异常模拟商家转账受理失败，驱动 service「受理失败 → payout failed」分支。默认 false（受理成功 processing）。
     */
    private boolean transferAcceptFail = false;

    public MockWechatPayClient(WechatPayProperties properties) {
        log.warn("[gz-pay] MockWechatPayClient 已激活（client-mode != real）—— 不连真实微信通道，仅供 dev/单测/商户号未到位降级。");
    }

    @Override
    public String createJsapiOrder(UnifiedOrderRequest req) {
        String prepayId = MOCK_PREPAY_PREFIX + req.outTradeNo();
        log.info("[gz-pay-mock] createJsapiOrder out_trade_no={} amount={} → prepay_id={}",
            req.outTradeNo(), req.amountCent(), prepayId);
        return prepayId;
    }

    @Override
    public JsapiPayParams buildPayParams(String prepayId) {
        return new JsapiPayParams(
            String.valueOf(System.currentTimeMillis() / 1000),
            UUID.randomUUID().toString().replace("-", ""),
            "prepay_id=" + prepayId,
            "RSA",
            "mock_pay_sign_" + prepayId);
    }

    @Override
    public CallbackResult parseAndVerifyNotify(NotifyContext ctx) {
        // mock 不验签：把 body 当已解密 JSON 解析。body 非法 → throw（模拟验签/解析失败路径）。
        try {
            ObjectNode node = (ObjectNode) objectMapper.readTree(ctx.body());
            String transactionId = node.path("transaction_id").asText(null);
            String outTradeNo = node.path("out_trade_no").asText(null);
            String tradeState = node.path("trade_state").asText("SUCCESS");
            Long payerTotal = node.has("payer_total") ? node.get("payer_total").asLong() : null;
            if (outTradeNo == null || transactionId == null) {
                throw new WechatPayVerifyException("mock 回调 body 缺 out_trade_no / transaction_id");
            }
            return new CallbackResult(transactionId, outTradeNo, tradeState, payerTotal, null, ctx.body());
        } catch (WechatPayVerifyException e) {
            throw e;
        } catch (Exception e) {
            throw new WechatPayVerifyException("mock 回调 body 解析失败: " + e.getMessage(), e);
        }
    }

    /**
     * 构造 mock 回调 body（测试 / mp test.vue 模拟支付成功用）。
     *
     * @param outTradeNo    业务订单号
     * @param transactionId 模拟微信交易号
     * @param payerTotal    支付金额（分）
     * @return JSON body 字符串
     */
    public String buildMockCallbackBody(String outTradeNo, String transactionId, long payerTotal) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("out_trade_no", outTradeNo);
        node.put("transaction_id", transactionId);
        node.put("trade_state", "SUCCESS");
        node.put("payer_total", payerTotal);
        return node.toString();
    }

    // ============================================================
    //  GZ-PAY-102 主动查单 / 关单（mock 实现）
    // ============================================================

    @Override
    public QueryResult queryByOutTradeNo(String outTradeNo) {
        String txnId = "SUCCESS".equalsIgnoreCase(queryTradeState) ? "mock_query_txn_" + outTradeNo : null;
        Long payerTotal = "SUCCESS".equalsIgnoreCase(queryTradeState) ? queryPayerTotal : null;
        String rawBody = "{\"out_trade_no\":\"" + outTradeNo + "\",\"trade_state\":\"" + queryTradeState
            + "\",\"transaction_id\":" + (txnId == null ? "null" : "\"" + txnId + "\"")
            + ",\"source\":\"query\"}";
        log.info("[gz-pay-mock] queryByOutTradeNo out_trade_no={} → trade_state={}", outTradeNo, queryTradeState);
        return new QueryResult(outTradeNo, txnId, queryTradeState, payerTotal, null, rawBody);
    }

    @Override
    public void closeOrder(String outTradeNo) {
        closeOrderCallCount++;
        log.info("[gz-pay-mock] closeOrder out_trade_no={}（mock 直接成功，累计调 {} 次）", outTradeNo, closeOrderCallCount);
    }

    /**
     * 单测注入查单 trade_state（GZ-PAY-102 AC3）。驱动 scanAndReconcile 各分支。
     *
     * @param tradeState SUCCESS / NOTPAY / USERPAYING / CLOSED / REVOKED / PAYERROR
     */
    public void setQueryTradeState(String tradeState) {
        this.queryTradeState = tradeState;
    }

    /**
     * 单测注入 SUCCESS 场景的 payer_total（分）。
     *
     * @param payerTotalCent 用户实付金额（分）
     */
    public void setQueryPayerTotal(Long payerTotalCent) {
        this.queryPayerTotal = payerTotalCent;
    }

    /**
     * 取 mock 关单累计调用次数（GZ-PAY-102 AC8 断言：超 30min NOTPAY → closeOrder 被调）。
     *
     * @return closeOrder 累计调用次数
     */
    public int getCloseOrderCallCount() {
        return closeOrderCallCount;
    }

    // ============================================================
    //  GZ-PAY-103 退款 / 退款回调（mock 实现）
    // ============================================================

    @Override
    public RefundResult refund(RefundRequest req) {
        if (refundAcceptFail) {
            // 模拟微信受理失败（余额不足 / 参数错误等）—— service 据此回滚 paid（AC9 受理失败分支）
            throw new ServiceException("[gz-pay-mock] 模拟退款受理失败 out_refund_no=" + req.outRefundNo());
        }
        String refundId = "mock_refund_id_" + req.outRefundNo();
        log.info("[gz-pay-mock] refund out_trade_no={} out_refund_no={} amount={} → 受理成功 refund_id={}（PROCESSING）",
            req.outTradeNo(), req.outRefundNo(), req.refundAmountCent(), refundId);
        String rawBody = "{\"out_refund_no\":\"" + req.outRefundNo() + "\",\"refund_id\":\"" + refundId
            + "\",\"status\":\"PROCESSING\"}";
        return new RefundResult(refundId, "PROCESSING", rawBody);
    }

    @Override
    public RefundCallbackResult parseAndVerifyRefundNotify(NotifyContext ctx) {
        // mock 不验签：把 body 当已解密 JSON 解析。body 非法 → throw（模拟验签/解析失败路径）。
        try {
            ObjectNode node = (ObjectNode) objectMapper.readTree(ctx.body());
            String refundId = node.path("refund_id").asText(null);
            String outRefundNo = node.path("out_refund_no").asText(null);
            String outTradeNo = node.path("out_trade_no").asText(null);
            String refundStatus = node.path("refund_status").asText("SUCCESS");
            if (outRefundNo == null || refundId == null) {
                throw new WechatPayVerifyException("mock 退款回调 body 缺 out_refund_no / refund_id");
            }
            return new RefundCallbackResult(refundId, outRefundNo, outTradeNo, refundStatus, ctx.body());
        } catch (WechatPayVerifyException e) {
            throw e;
        } catch (Exception e) {
            throw new WechatPayVerifyException("mock 退款回调 body 解析失败: " + e.getMessage(), e);
        }
    }

    /**
     * 构造 mock 退款回调 body（GZ-PAY-103 单测 / 模拟退款回调用）。
     *
     * @param outTradeNo   原业务支付订单号
     * @param outRefundNo  商户退款单号（refund_no）
     * @param refundId     模拟微信退款单号
     * @param refundStatus 退款状态（SUCCESS / ABNORMAL / CLOSED）
     * @return JSON body 字符串
     */
    public String buildMockRefundCallbackBody(String outTradeNo, String outRefundNo, String refundId, String refundStatus) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("out_trade_no", outTradeNo);
        node.put("out_refund_no", outRefundNo);
        node.put("refund_id", refundId);
        node.put("refund_status", refundStatus);
        return node.toString();
    }

    /**
     * 单测注入退款受理失败（GZ-PAY-103 AC9：true → refund 抛异常驱动回滚 paid 分支）。
     *
     * @param fail true = 模拟受理失败
     */
    public void setRefundAcceptFail(boolean fail) {
        this.refundAcceptFail = fail;
    }

    // ============================================================
    //  GZ-PAY-104 资金账单（mock 实现）
    // ============================================================

    @Override
    public FundFlowBill downloadFundFlowBill(LocalDate billDate) {
        String csv = overrideBillCsv != null
            ? overrideBillCsv
            : FundFlowBillCsvBuilder.defaultMockBill(billDate);
        // 默认返回 CSV 真实 sha1（service 校验通过）；overrideHashValue 非 null 时返回坏 hash 驱动校验失败路径
        String hash = overrideHashValue != null
            ? overrideHashValue
            : FundFlowBillCsvBuilder.sha1Hex(csv);
        log.info("[gz-pay-mock] downloadFundFlowBill bill_date={} → {} 行 CSV（mock）", billDate, csv.split("\n").length);
        return new FundFlowBill(billDate, csv, hash);
    }

    /**
     * 单测注入资金账单 CSV（GZ-PAY-104 AC7）。传 null 恢复内置默认账单。
     *
     * @param csv 完整 CSV 文本（含表头 + 数据行 + 汇总行）
     */
    public void setOverrideBillCsv(String csv) {
        this.overrideBillCsv = csv;
    }

    /**
     * 单测注入微信声明 hash（GZ-PAY-104 AC3：传与 CSV 不符的 hash 制造校验失败）。传 null 恢复真实 sha1。
     *
     * @param hash 微信声明 sha1
     */
    public void setOverrideHashValue(String hash) {
        this.overrideHashValue = hash;
    }

    // ============================================================
    //  GZ-PAY-105 反向打款（商家转账到零钱，mock 实现，ADR-0006）
    // ============================================================

    @Override
    public TransferResult transferToUserWallet(TransferRequest req) {
        if (transferAcceptFail) {
            log.warn("[gz-pay-mock] transferToUserWallet 注入受理失败 out_payout_no={}", req.outPayoutNo());
            throw new IllegalStateException("mock 商家转账受理失败（transferAcceptFail=true）");
        }
        // mock 受理成功：返回稳定占位 payout_id + batch_id（基于 out_payout_no 派生，幂等可复算），状态 ACCEPTED → service 落 processing
        String payoutId = MOCK_PAYOUT_ID_PREFIX + req.outPayoutNo();
        String batchId = MOCK_BATCH_ID_PREFIX + req.outPayoutNo();
        String rawBody = String.format(
            "{\"out_bill_no\":\"%s\",\"transfer_bill_no\":\"%s\",\"batch_id\":\"%s\",\"state\":\"ACCEPTED\",\"amount\":%d,\"openid\":\"%s\"}",
            req.outPayoutNo(), payoutId, batchId, req.amountCent(), req.receiverOpenid());
        log.info("[gz-pay-mock] transferToUserWallet out_payout_no={} amount_cent={} → ACCEPTED（processing）payout_id={} batch_id={}",
            req.outPayoutNo(), req.amountCent(), payoutId, batchId);
        return new TransferResult(payoutId, batchId, "ACCEPTED", rawBody);
    }

    @Override
    public TransferQueryResult queryTransferByOutNo(String outPayoutNo) {
        String payoutId = MOCK_PAYOUT_ID_PREFIX + outPayoutNo;
        String batchId = MOCK_BATCH_ID_PREFIX + outPayoutNo;
        String failReason = "FAIL".equals(queryTransferState) ? queryTransferFailReason : null;
        String rawBody = String.format(
            "{\"out_bill_no\":\"%s\",\"transfer_bill_no\":\"%s\",\"batch_id\":\"%s\",\"state\":\"%s\"%s}",
            outPayoutNo, payoutId, batchId, queryTransferState,
            failReason == null ? "" : ",\"fail_reason\":\"" + failReason + "\"");
        log.info("[gz-pay-mock] queryTransferByOutNo out_payout_no={} → transfer_state={}", outPayoutNo, queryTransferState);
        return new TransferQueryResult(outPayoutNo, payoutId, batchId, queryTransferState, failReason, rawBody);
    }

    /**
     * 单测注入反向打款查单态（GZ-PAY-105 AC2/AC8）：PROCESSING（默认）/ SUCCESS / FAIL。
     *
     * @param state 转账查单态
     */
    public void setQueryTransferState(String state) {
        this.queryTransferState = state;
    }

    /**
     * 单测注入反向打款受理失败（GZ-PAY-105：true → transferToUserWallet 抛异常驱动 payout failed 分支）。
     *
     * @param fail true = 模拟受理失败
     */
    public void setTransferAcceptFail(boolean fail) {
        this.transferAcceptFail = fail;
    }
}
