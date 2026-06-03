package org.dromara.gz.common.pay.domain.bo;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 退款申请入参（GZ-PAY-103 AC 2）。
 *
 * <p><b>仅全额退款</b>（doc/10 §6.E5 / 强约束 #1）：<b>无 amountCent 入参</b> —— 退款金额由系统取原
 * {@code gz_pay_transaction.amount_cent}，杜绝前端传错金额（决策 D2）。运营人在 admin 仅填退款原因。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-PAY-103)
 */
@Data
public class RefundApplyBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 原支付交易行主键 id（前端从订单详情拿；后端取该单 amount_cent 作全额退款金额） */
    @NotNull(message = "transactionId 不能为空")
    private Long transactionId;

    /** 退款原因（必填，≤ 255） */
    @NotNull(message = "退款原因不能为空")
    @Size(max = 255, message = "退款原因不超过 255 字")
    private String reason;
}
