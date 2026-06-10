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
 * gz_recon_monthly — 月度对账单 entity（GZ-ADMIN-105）。
 *
 * <p>字段口径权威：doc/11 §9.2。业务流权威：doc/10 §10.N2（每月 1 日跑批前月）。</p>
 *
 * <p><b>分成口径（A/B 不交叉冲抵，逐字落地）</b>：</p>
 * <ul>
 *   <li>{@code gmvCent / refundCent / channelFeeCent} = SUM(gz_recon_daily 对应字段 WHERE business_month AND business_type)</li>
 *   <li>{@code settleCent} = MAX(0, gmv − refund − fee)（任一业务线负流水 → 该业务线该月 = 0，不倒贴/不冲减另一线，doc/10 Q10.4）</li>
 *   <li>{@code commissionCent} = settleCent × commissionRateBp / 10000（向下取整到分）</li>
 *   <li>A（preorder）/ B（gacha）<b>分别一条</b>，各自独立算分成（合同 §4.1.2）</li>
 * </ul>
 *
 * @author kevin-coder (sensenran-guzi · GZ-ADMIN-105)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("gz_recon_monthly")
public class GzReconMonthly extends TenantEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 业务月 yyyy-MM */
    private String businessMonth;

    /** preorder（A）/ gacha（B） */
    private String businessType;

    /** 当月 GMV（分） */
    private Long gmvCent;

    /** 当月退款（分） */
    private Long refundCent;

    /** 当月通道费（分） */
    private Long channelFeeCent;

    /** 实际到账流水（分）= MAX(0, gmv − refund − fee)（负流水该线该月=0） */
    private Long settleCent;

    /** 分成比例（千分之，400=4%），取 sys_config */
    private Integer commissionRateBp;

    /** 乙方应得分成（分）= settleCent × commissionRateBp / 10000（向下取整） */
    private Long commissionCent;

    /** generating / generated / confirmed / settled / exception */
    private String status;

    /** 甲方确认人（微信回复"确认"视为书面，合同 §4.2.2） */
    private String confirmedBy;

    /** 确认时间 */
    private LocalDateTime confirmedTime;

    /** 季度合并支付时间 */
    private LocalDateTime settledTime;

    /** 导出 Excel 文件 OSS key */
    private String excelExportObjectKey;

    /** 备注 */
    private String remark;

    /** 软删（0=正常 / 2=删除） */
    @TableLogic
    private String delFlag;
}
