package org.dromara.gz.jp.domain.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 标记购买失败后<b>逐行</b>的退款结果（GZ-JP-107，FLOW:F-JP-04.step2）。
 *
 * <p><b>为什么要逐行而不是一个总数</b>：一次勾 30 行，可能 28 行退款受理成功、1 行之前已经退过、
 * 1 行被微信拒了。只给「成功 28」会让另外两行的钱悄悄消失在统计里 ——
 * 而这是资金链路，每一分钱的去向都得能当场说清楚。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-JP-107)
 */
@Data
public class GzJpRefundLineVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 订单商品行 id（string） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long itemId;

    /** 退款单 id（string；本次未产生退款单时为 null） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long refundId;

    /** 商户退款单号 JPRF-yyyyMMdd-6位（= 微信 out_refund_no） */
    private String refundNo;

    /** ★ 本行退款金额（分）= 该行 amount_cent，不是整单 */
    private Long refundAmountCent;

    /** 退款单状态 code：refunding / refunded / refund_failed（未产生退款单时为 null） */
    private String refundStatus;

    /** 退款单状态中文（后端给，前端别自己维护映射） */
    private String refundStatusLabel;

    /** 微信是否已受理本次提交（false 且 refundStatus=refund_failed → 看 failReason） */
    private Boolean accepted;

    /** 失败原因（受理被拒时给，admin 直接展示，不静默吞） */
    private String failReason;

    /**
     * 本次没有发起新退款的原因 code（{@code GzJpRefundSkipReason}）。
     *
     * <p>非 null 即表示这一行<b>本次没花钱</b>（之前已退 / 正在退 / 上次失败待人工重试）。</p>
     */
    private String skipReasonCode;

    /** 没有发起新退款的原因（中文） */
    private String skipReason;
}
