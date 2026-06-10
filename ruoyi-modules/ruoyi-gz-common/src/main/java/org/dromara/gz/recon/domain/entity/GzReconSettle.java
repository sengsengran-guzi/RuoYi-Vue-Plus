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
import java.time.LocalDateTime;

/**
 * gz_recon_settle — 季度结算记录 entity（GZ-ADMIN-105）。
 *
 * <p>字段口径权威：doc/11 §9.4。业务流权威：doc/10 §10.N4（季度末月 15 日前合并支付）。</p>
 *
 * <p><b>季度结算口径（逐字落地）</b>：</p>
 * <ul>
 *   <li>{@code commissionTotalCent} = SUM(gz_recon_monthly.commission_cent WHERE quarter)（A + B 业务线相加）</li>
 *   <li>{@code maintenanceTotalCent} = gz.commission.maintenance.monthly.cent × 3（300000×3=900000，¥3000×3 月，合同 §4.6）</li>
 *   <li>{@code payableTotalCent} = commissionTotalCent + maintenanceTotalCent（分成 + 月维护费合并一项）</li>
 * </ul>
 *
 * @author kevin-coder (sensenran-guzi · GZ-ADMIN-105)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("gz_recon_settle")
public class GzReconSettle extends TenantEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 季度 yyyy-Q1 / Q2 / Q3 / Q4 */
    private String quarter;

    /** 季度分成合计（分）= SUM(monthly.commission_cent WHERE quarter)（A+B 相加） */
    private Long commissionTotalCent;

    /** 月度维护费合计（分）= 月维护费 × 3（合同 §4.6） */
    private Long maintenanceTotalCent;

    /** 季度应付合计（分）= commissionTotalCent + maintenanceTotalCent */
    private Long payableTotalCent;

    /** 实际到账（分，A 录） */
    private Long paidAmountCent;

    /** 实际支付时间（A 录） */
    private LocalDateTime paidTime;

    /** 乙方发票号（合同 §4.7） */
    private String invoiceNo;

    /** 发票金额（分） */
    private Long invoiceAmountCent;

    /** pending / paid / invoiced / closed */
    private String status;

    /** 备注 */
    private String remark;

    /** 软删（0=正常 / 2=删除） */
    @TableLogic
    private String delFlag;
}
