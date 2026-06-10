package org.dromara.gz.recon.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
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
import java.time.LocalDate;

/**
 * gz_recon_daily — 每日跑批对账 entity（GZ-ADMIN-105）。
 *
 * <p>字段口径权威：doc/11 §9.1。业务流权威：doc/10 §10.N1（凌晨 02:00 跑批前一日）。</p>
 *
 * <p><b>金额口径（逐字落地，零容忍）</b>：</p>
 * <ul>
 *   <li>{@code systemGmvCent} = SUM(gz_pay_transaction.amount_cent WHERE business_type AND status='paid' AND DATE(paid_time)=business_day)</li>
 *   <li>{@code systemRefundCent} = SUM(gz_pay_refund.refund_amount_cent WHERE 关联 transaction.business_type AND DATE(refunded_time)=business_day)（退款按 refunded_time 当日归属，C4）</li>
 *   <li>{@code systemFeeCent} = SUM(gz_pay_transaction.fee_cent WHERE business_type AND status='paid' AND DATE(paid_time)=business_day)（fee_cent 来自 PAY-104）</li>
 *   <li>{@code systemSettleCent} = systemGmvCent − systemRefundCent − systemFeeCent（实际到账流水，合同 §1.5）</li>
 * </ul>
 *
 * <p>幂等：UNIQUE(tenant_id, business_day, business_type) + UPSERT（同日重跑覆盖不累加）。
 * channel_* / diff_* 在 mock 模式留 null（无真实账单，PAY 拉 fundflowbill 后回写比对）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-ADMIN-105)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("gz_recon_daily")
public class GzReconDaily extends TenantEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 业务日（北京时间，跑批前一日） */
    private LocalDate businessDay;

    /** preorder（业务线 A）/ gacha（业务线 B）；test/pindou 不进对账 */
    private String businessType;

    /** 系统侧 GMV（分） */
    private Long systemGmvCent;

    /** 系统侧退款（分），按 refunded_time 当日归属 */
    private Long systemRefundCent;

    /** 系统侧通道费（分），来自 PAY-104 拉 fundflowbill 回写 */
    private Long systemFeeCent;

    /** 实际到账流水（分）= gmv − refund − fee（合同 §1.5） */
    private Long systemSettleCent;

    /** 微信商户后台 GMV（比对用，mock 留 null） */
    private Long channelGmvCent;

    /** 商户后台通道费（比对用，mock 留 null） */
    private Long channelFeeCent;

    /** 差异（system − channel，应为 0；mock 留 null） */
    private Long diffGmvCent;

    /** 差异（system − channel，应为 0；mock 留 null） */
    private Long diffFeeCent;

    /** generating / generated / exception（gz_recon_status 字典） */
    private String status;

    /** 备注 */
    private String remark;

    /** 软删（0=正常 / 2=删除） */
    @TableLogic
    private String delFlag;
}
