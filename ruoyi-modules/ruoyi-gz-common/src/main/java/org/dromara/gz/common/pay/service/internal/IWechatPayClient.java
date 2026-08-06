package org.dromara.gz.common.pay.service.internal;

import java.time.LocalDate;

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
     * <p><b>appid 必须由调用方从下单上下文传入</b>（ADR-0019 §3，多小程序）：签名的第一个因子就是 appid，
     * 与实际调起方（哪个小程序）不一致时微信侧验签直接不过。这里刻意做成<b>必填形参而非读全局配置</b> ——
     * 多 appid 改造中「只改了统一下单、漏了本方法」是最容易犯且最难查的错（下单返 200、日志全绿，
     * 前端调起时才报签名错，排查方向被日志带偏）；做成形参后编译器会强制每个调用点交代 appid。</p>
     *
     * @param prepayId 统一下单返回的 prepay_id
     * @param appid    下单时所用的小程序 appid（<b>必须与 {@link #createJsapiOrder} 传入的同一个</b>）
     * @return 5 参签名（timeStamp / nonceStr / package / signType / paySign）
     */
    JsapiPayParams buildPayParams(String prepayId, String appid);

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
     * 按 out_trade_no 主动查单（GZ-PAY-102，doc/10 §6.N7 主动查单兜底）。
     *
     * <p>对应微信 V3 {@code GET /v3/pay/transactions/out-trade-no/{out_trade_no}?mchid=...}。
     * 用于回调丢失 / 延迟时主动核对微信侧真实交易状态（{@code trade_state}）。</p>
     *
     * <p>real 实现用 SDK {@code JsapiService.queryOrderByOutTradeNo} 拿 {@link Transaction}；
     * mock（{@link MockWechatPayClient}）返回可配置 {@code tradeState}（默认 NOTPAY），驱动单测覆盖
     * SUCCESS / NOTPAY / USERPAYING / CLOSED / REVOKED / PAYERROR 各分支。</p>
     *
     * @param outTradeNo 业务订单号
     * @return 查单结果（trade_state / transaction_id / payer_total / 原始报文）；订单微信侧不存在时
     *         trade_state 实现按微信约定返回（real 抛 SDK NOT_FOUND，mock 不模拟此态）
     */
    QueryResult queryByOutTradeNo(String outTradeNo);

    /**
     * 关闭订单（GZ-PAY-102 AC 8，doc/10 §6 状态机 pending → timeout 防偷付）。
     *
     * <p>对应微信 V3 {@code POST /v3/pay/transactions/out-trade-no/{out_trade_no}/close}。
     * 用户超 30min 仍未付（查单仍 NOTPAY）时调用，避免用户事后偷付造成本地与微信不一致。</p>
     *
     * <p>real 用 SDK {@code JsapiService.closeOrder}；mock 直接返回成功（不连微信）。关单接口幂等：
     * 微信侧已关闭 / 已支付时重复调用按微信约定（real 实现透传 SDK 行为）。</p>
     *
     * @param outTradeNo 业务订单号
     */
    void closeOrder(String outTradeNo);

    /**
     * 拉取资金账单（GZ-PAY-104，doc/11 F4.3 / F9.2 / doc/10 §10 E6）。
     *
     * <p>资金账单（{@code /v3/bill/fundflowbill}）是 {@code gz_pay_transaction.fee_cent}（真实通道
     * 手续费）的<b>唯一来源</b>：V3 支付回调 body 不含 fee，固定费率也不算数（doc/10 §10 E6）。</p>
     *
     * <p>real 实现两步：① 调 {@code /v3/bill/fundflowbill?bill_date=yyyy-MM-dd} 拿
     * {@code download_url}（含 token）+ {@code hash_type}(SHA1) + {@code hash_value} →
     * ② 下载 gzip 文件解压成 CSV 文本。本方法返回<b>已解压的 CSV 文本</b> + 微信声明的
     * {@code hash_value}（service 据此自算 sha1 校验，不匹配抛错告警，AC3）。mock 返回构造的 CSV +
     * 与之匹配的 sha1（可通过 {@code corruptHash} 控制制造校验失败路径，仅 mock 用）。</p>
     *
     * <p>账单 T+1 可用；跑批默认处理"前一业务日"（北京时间）。</p>
     *
     * @param billDate 账单业务日（yyyy-MM-dd）
     * @return 已解压 CSV 文本 + 微信声明的 sha1 hash（service 自校验）
     */
    FundFlowBill downloadFundFlowBill(LocalDate billDate);

    /**
     * 发起全额退款（GZ-PAY-103，doc/10 §6.N9 → {@code POST /v3/refund/domestic/refunds}）。
     *
     * <p>V1.1 仅全额退款（一笔对一单）：{@code refundAmountCent} = 原支付单 {@code amount_cent}，
     * {@code totalAmountCent} = 原订单总额（微信要求传原单总额做校验，全额退款两者相等）。</p>
     *
     * <p>real 用 SDK {@code RefundService.create} 提交退款申请（同步返回受理结果），最终退款结果走异步
     * 退款回调 {@link #parseAndVerifyRefundNotify}；mock（{@link MockWechatPayClient}）默认返回受理成功，
     * 可注入 {@code refundAcceptFail} 驱动「受理失败 → 回滚 paid」分支（AC 9）。</p>
     *
     * @param req 退款请求（out_trade_no / out_refund_no / refundAmountCent / totalAmountCent / reason / notifyUrl）
     * @return 受理结果（微信退款单号 refund_id + 受理状态；受理失败 real 抛 SDK 异常 / mock 抛业务异常）
     */
    RefundResult refund(RefundRequest req);

    /**
     * 解析 + 验签微信退款回调（GZ-PAY-103，doc/10 §6.N10，独立 endpoint {@code /api/pay/v3/refund-notify}）。
     *
     * <p>退款回调 resource 语义与支付回调不同（退款单维度，含 {@code refund_status} 而非 {@code trade_state}）。
     * real 复用同一套 SDK {@code NotificationParser} 的 AES-GCM 验签 + 解密机制（与支付回调同套，强约束 #2），
     * 但解析成 {@link RefundCallbackResult}（refund_id / out_refund_no / out_trade_no / refund_status）；
     * mock 把 body 当已解密 JSON 解析。</p>
     *
     * @param ctx 回调原始 HTTP 上下文（headers + body，复用 {@link NotifyContext}）
     * @return 解密后的退款回调结果
     * @throws WechatPayVerifyException 验签失败
     */
    RefundCallbackResult parseAndVerifyRefundNotify(NotifyContext ctx);

    /**
     * 反向打款：商家转账到用户零钱（GZ-PAY-105，ADR-0006，doc/10 §14 / doc/11 §4.8）。
     *
     * <p>对应微信 V3「商家转账」（{@code POST /v3/transfer/batches}，平台 → 用户零钱）。承载回收返现等
     * 反向资金流（与收款方向相反）。幂等键 = {@code out_payout_no}（商户侧 PAYOUT- 单号），微信侧以
     * out_batch_no 去重，重复提交不重复打款。</p>
     *
     * <p>real（{@link WechatPayV3ClientImpl}）接 V3 transferbatch 接口 —— <b>当前留 hook</b>：商户「商家转账」
     * 权限需单独申请（ADR-0006 Consequences），且 transferbatch SDK 依赖待 Kevin 批准（铁律 #8），故 real
     * 暂抛 {@link UnsupportedOperationException}，不私引 {@code com.wechat.pay.java...transferbatch}。
     * mock（{@link MockWechatPayClient}）返回固定占位 {@code payout_id} + {@code batch_id}，状态置
     * {@code processing}（受理成功），驱动 {@code created→processing→success} 全链路单测 + failed 分支。</p>
     *
     * @param req 转账请求（out_payout_no / receiver_openid / amount_cent / transfer_remark / business_order_no）
     * @return 受理结果（payout_id + batch_id + 受理态；mock 恒受理成功 processing，可注入 acceptFail 驱动失败）
     */
    TransferResult transferToUserWallet(TransferRequest req);

    /**
     * 按商户单号查反向打款单（GZ-PAY-105，ADR-0006 §3 主动查单优先，doc/10 §14）。
     *
     * <p>对应微信 V3 {@code GET /v3/transfer/batches/out-batch-no/{out_batch_no}}。商家转账回调到达性弱、
     * 部分场景无回调，<b>微信官方建议主动查单为准</b> —— SnailJob（{@code gzPayoutQueryTask}）周期扫
     * {@code processing} 态 payout 单调本方法核对真实状态，命中 {@code SUCCESS}/{@code FAIL} 推进终态。</p>
     *
     * <p>real <b>同样留 hook</b>（抛 {@link UnsupportedOperationException}，待权限 + SDK 批准）。mock
     * 返回可配置 {@code transferState}（默认 {@code PROCESSING}；调 {@code setQueryTransferState} 注入
     * {@code SUCCESS}/{@code FAIL}）驱动单测覆盖 success / failed 分支。</p>
     *
     * @param outPayoutNo 商户侧反向打款单号（PAYOUT-）
     * @return 查单结果（transfer_state / payout_id / batch_id / 原始报文）
     */
    TransferQueryResult queryTransferByOutNo(String outPayoutNo);

    /**
     * 统一下单请求。
     *
     * @param outTradeNo  业务订单号
     * @param amountCent  金额（分）
     * @param openid      付款用户 openid —— <b>必须与 {@code appid} 同源</b>（openid 是 appid 维度标识，
     *                    跨小程序不通用），否则微信返「openid 与 appid 不匹配」
     * @param description 商品描述
     * @param appid       下单所用的小程序 appid（ADR-0019 §3：从下单上下文携带，不再读全局配置）
     */
    record UnifiedOrderRequest(String outTradeNo, long amountCent, String openid, String description,
                               String appid) {
    }

    /**
     * 退款请求（GZ-PAY-103，{@link #refund}）。
     *
     * @param outTradeNo      原业务支付订单号（关联原支付单）
     * @param outRefundNo     商户退款单号（refund_no，全局唯一，对应微信 out_refund_no）
     * @param refundAmountCent 退款金额（分，全额 = 原单 amount_cent）
     * @param totalAmountCent  原订单总额（分，微信校验用，全额退款 = refundAmountCent）
     * @param reason          退款原因
     * @param notifyUrl       退款回调地址（/api/pay/v3/refund-notify 完整 URL）
     */
    record RefundRequest(String outTradeNo, String outRefundNo, long refundAmountCent,
                         long totalAmountCent, String reason, String notifyUrl) {
    }

    /**
     * 退款受理结果（GZ-PAY-103，{@link #refund} 同步返回）。
     *
     * <p>{@code refundId} = 微信退款单号（wechat_refund_id，受理即返回）；{@code status} = 微信退款单
     * 状态（{@code PROCESSING} 处理中 / {@code SUCCESS} 已成功 / {@code ABNORMAL} 异常 / {@code CLOSED} 已关闭）。
     * 受理成功通常 {@code PROCESSING}，最终结果走异步退款回调。</p>
     *
     * @param refundId 微信退款单号（wechat_refund_id）
     * @param status   退款单受理状态
     * @param rawBody  受理返回原始报文（入审计）
     */
    record RefundResult(String refundId, String status, String rawBody) {
    }

    /**
     * 退款回调解析结果（GZ-PAY-103，{@link #parseAndVerifyRefundNotify}）。
     *
     * <p>{@code refundStatus} 取值（微信 V3 退款回调约定）：{@code SUCCESS}（退款成功）/
     * {@code ABNORMAL}（退款异常）/ {@code CLOSED}（退款关闭）。SUCCESS → 推进 refunded；
     * ABNORMAL/CLOSED → 标 failed 留人工（doc/10 §6.E4）。</p>
     *
     * @param refundId      微信退款单号（wechat_refund_id，幂等基础）
     * @param outRefundNo   商户退款单号（refund_no）
     * @param outTradeNo    原业务支付订单号
     * @param refundStatus  退款状态（SUCCESS / ABNORMAL / CLOSED）
     * @param decryptedBody 解密后完整 JSON（入 callback_log raw_body）
     */
    record RefundCallbackResult(String refundId, String outRefundNo, String outTradeNo,
                                String refundStatus, String decryptedBody) {
    }

    /**
     * 资金账单下载结果（已解压 CSV + 微信声明的 sha1 hash，GZ-PAY-104）。
     *
     * @param billDate     账单业务日
     * @param csvContent   已 gzip 解压的完整 CSV 文本（含表头 + 数据行 + 汇总行）
     * @param hashValue    微信声明的文件 sha1（service 自算 csvContent 的 sha1 比对，AC3）
     */
    record FundFlowBill(LocalDate billDate, String csvContent, String hashValue) {
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

    /**
     * 主动查单结果（GZ-PAY-102，{@link #queryByOutTradeNo}）。
     *
     * <p>{@code tradeState} 取值（微信 V3 约定）：{@code SUCCESS}（已支付）/ {@code NOTPAY}（未支付）/
     * {@code USERPAYING}（支付中）/ {@code CLOSED}（已关闭）/ {@code REVOKED}（已撤销）/
     * {@code PAYERROR}（支付失败）/ {@code REFUND}（转入退款）。{@code rawBody} 入 callback_log raw_body
     * （区分查单补单 vs 被动通知补单的来源，决策 D5：靠 raw_body 来源而非新增列）。</p>
     *
     * @param outTradeNo    业务订单号
     * @param transactionId 微信交易号（NOTPAY 等未成交态可能为 null）
     * @param tradeState    交易状态
     * @param payerTotal    用户实际支付金额（分，未成交为 null）
     * @param feeCent       通道手续费（分，查单不给，real 填 null，由 PAY-104 对账回写）
     * @param rawBody       查单返回原始报文（入 callback_log raw_body）
     */
    record QueryResult(String outTradeNo, String transactionId, String tradeState,
                       Long payerTotal, Long feeCent, String rawBody) {
    }

    /**
     * 反向打款请求（GZ-PAY-105，{@link #transferToUserWallet}，doc/11 §4.8）。
     *
     * @param outPayoutNo     商户侧反向打款单号（PAYOUT-yyyyMMdd-6位序号，幂等键）
     * @param receiverOpenid  收款用户 openid（商家转账必需，从回收预约单快照取）
     * @param amountCent      转账金额（分，= gz_recycle_appointment.final_amount_cent）
     * @param transferRemark  转账备注（用户零钱可见）
     * @param businessOrderNo 业务订单号（= 回收预约号 RCY-，溯源）
     */
    record TransferRequest(String outPayoutNo, String receiverOpenid, long amountCent,
                           String transferRemark, String businessOrderNo) {
    }

    /**
     * 反向打款受理结果（GZ-PAY-105，{@link #transferToUserWallet} 同步返回）。
     *
     * <p>{@code payoutId} = 微信侧转账单号；{@code batchId} = 转账批次号（DB UNIQUE 幂等关键，ADR-0006 §5）；
     * {@code state} = 受理态（{@code ACCEPTED} 受理成功 → 本地 processing）。受理失败 real 抛 SDK 异常 /
     * mock 抛业务异常（注入 acceptFail）。</p>
     *
     * @param payoutId 微信侧转账单号（受理后返回）
     * @param batchId  转账批次号（幂等基础，UNIQUE）
     * @param state    受理态（ACCEPTED）
     * @param rawBody  受理返回原始报文（入审计）
     */
    record TransferResult(String payoutId, String batchId, String state, String rawBody) {
    }

    /**
     * 反向打款查单结果（GZ-PAY-105，{@link #queryTransferByOutNo}，ADR-0006 §3 主动查单为准）。
     *
     * <p>{@code transferState} 取值（微信 V3 商家转账约定，本系统归一）：{@code PROCESSING}（处理中）/
     * {@code SUCCESS}（已到账，→ payout success + 写 transferred_time）/ {@code FAIL}（失败，→ payout failed
     * + 写 fail_reason，可重试重置 created）。{@code rawBody} 入 {@code gz_pay_payout_callback_log.raw_body}。</p>
     *
     * @param outPayoutNo   商户侧反向打款单号
     * @param payoutId      微信侧转账单号（PROCESSING 时可能为 null）
     * @param batchId       转账批次号
     * @param transferState 转账状态（PROCESSING / SUCCESS / FAIL）
     * @param failReason    失败原因（FAIL 时给，否则 null）
     * @param rawBody       查单返回原始报文（入 payout_callback_log raw_body）
     */
    record TransferQueryResult(String outPayoutNo, String payoutId, String batchId,
                               String transferState, String failReason, String rawBody) {
    }
}
