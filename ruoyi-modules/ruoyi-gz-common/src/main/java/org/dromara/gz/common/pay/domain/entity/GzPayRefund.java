package org.dromara.gz.common.pay.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.dromara.common.tenant.core.TenantEntity;

import java.io.Serial;
import java.time.LocalDateTime;

/**
 * gz_pay_refund — 退款单 entity（GZ-PAY-103）。
 *
 * <p>字段口径权威：doc/11 §4.4。业务流权威：doc/10 §6（状态机
 * {@code 已支付 → 退款中 → 已退款 / 退款失败回滚已支付}）。</p>
 *
 * <p><b>仅全额退款</b>（doc/10 §6.E5）：一笔对一单，{@code refundAmountCent} = 原支付单
 * {@code amount_cent}（系统取，不接受前端传值）。退款不更新 {@code gz_pay_transaction.amount_cent}，
 * 仅推进 transaction.status（paid → refunding → refunded / 回滚 paid，doc/11 §4.2）。</p>
 *
 * <p><b>幂等基础</b>：{@code wechat_refund_id} UNIQUE(tenant_id, wechat_refund_id)（NULL 允许并存）+
 * {@code refund_no} UNIQUE(tenant_id, refund_no)。退款回调按 refund_no / wechat_refund_id 去重 +
 * SELECT FOR UPDATE 锁行（doc/10 §6.N10）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-PAY-103)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("gz_pay_refund")
public class GzPayRefund extends TenantEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键（不暴露给前端） */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 商户退款单号 RF-yyyyMMdd-6位序号 — UNIQUE(tenant_id, refund_no) */
    private String refundNo;

    /** 微信交易号（FK → gz_pay_transaction.transaction_id，原支付单微信侧标识） */
    private String transactionId;

    /** 原业务支付订单号（FK → gz_pay_transaction.out_trade_no） */
    private String outTradeNo;

    /** 微信退款单号（受理 / 回调写）— UNIQUE(tenant_id, wechat_refund_id) NULL 允许并存 */
    private String wechatRefundId;

    /** 退款金额（分），全额 = 原单 amount_cent（系统取） */
    private Long refundAmountCent;

    /** 退款原因（admin 必填，≤ 255） */
    private String reason;

    /** refunding / refunded / failed（默认 refunding，doc/11 §4.4） */
    private String status;

    /** 触发人（当前登录 username，溯源用，不用 operator_id） */
    private String triggeredBy;

    /** 触发时间（apply 时写） */
    private LocalDateTime triggeredTime;

    /** 退款完成时间（回调 SUCCESS 写） */
    private LocalDateTime refundedTime;

    /** 软删（0=正常 / 1=删除） */
    @TableLogic
    private String delFlag;

    /**
     * 原交易行主键 id（非持久化）—— createRefunding 事务内把锁到的交易行 id 带出来，供事务外调微信 /
     * 受理失败回滚时定位交易行，避免再查一次。{@code exist=false} 不映射到 DB 列。
     */
    @TableField(exist = false)
    private Long transientTransactionRowId;
}
