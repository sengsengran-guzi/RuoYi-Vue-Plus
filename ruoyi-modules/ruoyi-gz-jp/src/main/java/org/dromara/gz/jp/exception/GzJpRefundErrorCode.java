package org.dromara.gz.jp.exception;

/**
 * 行级退款业务错误码（GZ-JP-107，FLOW:F-JP-04）。
 *
 * <p>码段接 {@code GzJpFulfillErrorCode}（4108-4110 已被 106 批量履约占用），本卡取 <b>4111-4115</b>。
 * 下一个空号 <b>4116</b>。</p>
 *
 * <p><b>为什么退款也要独立码段</b>：admin 对不同失败的处置完全不同 ——「一行都标不了」要刷新看板、
 * 「原支付流水缺失」要找技术、「已在退款中」是提示不是错误、「超出原单总额」是数据异常必须告警。
 * 靠 msg 字符串匹配一定会错，而这是资金链路。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-JP-107)
 */
public final class GzJpRefundErrorCode {

    private GzJpRefundErrorCode() {
    }

    /**
     * 所选行<b>没有一行可以标记购买失败、也没有一行需要补发退款</b>。
     *
     * <p>典型成因：全部是未支付单的行 / 全部已发货完毕（终态）/ 全部已经退过款了。
     * 前端提示刷新看板后重试。</p>
     *
     * <p>★ 注意「行已是购买失败但还没退款」<b>不</b>报本码 —— 那会补建退款单并返回 200
     * （见 {@code IGzJpRefundService.markPurchaseFailedAndRefund} 的补退款语义）。</p>
     */
    public static final int NOTHING_MARKED = 4111;

    /**
     * 订单缺少可退款的原支付流水（{@code pay_transaction_id} 为空 / 流水行查不到）。
     *
     * <p>正常不可达：订单进 paid 时支付回调必然回填了流水 id。真出现说明数据被手工改过 ——
     * <b>宁可整行拒绝也不能凭空造一笔退款</b>，所以给独立码让 admin 立刻找技术，而不是重试。</p>
     */
    public static final int PAY_TXN_MISSING = 4112;

    /**
     * 本次退款会让该单已退总额<b>超过原支付单总额</b>。
     *
     * <p>理论不可达（Σ 行金额 = 订单总额 = 支付流水金额，且一行至多一条退款单），
     * 真触发说明金额数据已经不一致 —— 这是资金安全的最后一道兜底，宁可拒绝也不提交给微信。</p>
     */
    public static final int REFUND_EXCEEDS_TOTAL = 4113;

    /** 退款单不存在（重试 / 查询传了不存在的 id）。 */
    public static final int REFUND_NOT_FOUND = 4114;

    /**
     * 退款单当前状态不允许重试。
     *
     * <p>已 {@code refunded} 的不能重试（钱已退，重试等于退第二次）；
     * 刚发起还在等回调的 {@code refunding} 也不给重试（避免店员看不到结果就狂点）——
     * 只有超过静默期仍未回调的才放行。</p>
     */
    public static final int REFUND_RETRY_NOT_ALLOWED = 4115;
}
