package org.dromara.gz.common.pay.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.dromara.common.tenant.core.TenantEntity;

import java.io.Serial;
import java.time.LocalDateTime;

/**
 * gz_pay_payout_transaction — 反向打款单 entity（GZ-PAY-105，V1.2 商家转账到零钱）。
 *
 * <p>字段口径权威：doc/11 §4.8。业务流权威：doc/10 §14。决策：ADR-0006。</p>
 *
 * <p><b>反向资金流</b>（平台 → 用户零钱）：承载回收返现（business_type='recycle'，business_order_no=回收预约号 RCY-）。
 * <b>独立核算，不计 GMV、不参与 4% 分成</b>（合同 §4.1 / ADR-0006 Consequences）。</p>
 *
 * <p><b>双重幂等</b>（ADR-0006 §5）：out_payout_no UNIQUE(tenant_id, out_payout_no) + batch_id
 * UNIQUE(tenant_id, batch_id) NULL 允许并存 + {@code version} 乐观锁状态推进 + 一笔 business_order_no
 * 与 payout 单 1:1（建单前查已有非 failed/cancelled 单拒重复）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-PAY-105)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("gz_pay_payout_transaction")
public class GzPayPayoutTransaction extends TenantEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 业务出账单号 PAYOUT-yyyyMMdd-6位序号（幂等基础）— UNIQUE(tenant_id, out_payout_no) */
    private String outPayoutNo;

    /** recycle（V1.2 唯一反向出账业务）；预留扩展 */
    private String businessType;

    /** 业务订单号 = gz_recycle_appointment.appointment_no（RCY-，D14 回填） */
    private String businessOrderNo;

    /** FK → gz_user.id（收款用户） */
    private Long userId;

    /** 收款人 openid（商家转账必需，从回收预约单快照取） */
    private String receiverOpenid;

    /** 转账金额（分），= gz_recycle_appointment.final_amount_cent */
    private Long amountCent;

    /** created / processing / success / failed / cancelled（独立 PayoutStatus，doc/11 附录 A.16） */
    private String status;

    /** 微信侧转账单号（受理后返回） */
    private String payoutId;

    /** 转账批次号（幂等关键）— UNIQUE(tenant_id, batch_id) NULL 允许并存 */
    private String batchId;

    /** 转账成功时间（查单/回调确认 success 时写） */
    private LocalDateTime transferredTime;

    /** 失败原因（failed 时写）；可重试（重置 created） */
    private String failReason;

    /** 乐观锁版本（mybatis-plus @Version，状态推进） */
    @Version
    private Integer version;

    /** 备注 */
    private String remark;

    /** 软删（0=正常 / 1=删除） */
    @TableLogic
    private String delFlag;
}
