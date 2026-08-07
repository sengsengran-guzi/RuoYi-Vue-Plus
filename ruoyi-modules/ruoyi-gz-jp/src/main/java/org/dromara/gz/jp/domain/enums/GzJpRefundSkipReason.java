package org.dromara.gz.jp.domain.enums;

/**
 * 标记购买失败时，某一行<b>没有产生新退款单</b>的原因（GZ-JP-107，FLOW:F-JP-04.step2）。
 *
 * <p><b>与 {@link GzJpFulfillRejectReason} 的分工</b>：那个描述「这一行连购买失败都标不了」
 * （履约侧被拒）；本枚举描述「购买失败标上了 / 本来就是失败态，但这次没发起新退款」（退款侧跳过）。
 * 两者是<b>不同的轴</b>，混成一个枚举会让 admin 分不清「这行到底退没退钱」。</p>
 *
 * <p>全部都是<b>非错误</b>语义 —— 出现它们时接口仍返回 200，只是在明细里说明原因。
 * 真正的失败（受理被微信拒 / 回调 ABNORMAL）落的是 {@code refund_status=refund_failed}，
 * 不走本枚举。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-JP-107)
 */
public enum GzJpRefundSkipReason {

    /** 该行已有退款单且正在退款中（等微信回调）—— 重复点击 / 并发提交的正常结果 */
    ALREADY_REFUNDING("该行退款已在处理中"),

    /** 该行已退款完成 —— 钱已经回到客人微信，绝不再退第二次 */
    ALREADY_REFUNDED("该行已完成退款"),

    /**
     * 该行已有退款单但上次失败了 —— 本次<b>不</b>自动重发。
     *
     * <p>失败原因可能是金额 / 账户 / 微信侧限制，盲目重试只会一直失败并刷屏。
     * 走 admin 的「重新发起退款」按钮（带原因展示）由人确认后重试。</p>
     */
    PREVIOUS_ATTEMPT_FAILED("该行上次退款失败，请在退款单列表重新发起"),

    /** 行金额为 0（理论不存在）—— 无款可退，直接置已退款，不调微信（微信不接受 0 元退款） */
    ZERO_AMOUNT("行金额为 0，无需退款");

    private final String message;

    GzJpRefundSkipReason(String message) {
        this.message = message;
    }

    public String getMessage() {
        return message;
    }
}
