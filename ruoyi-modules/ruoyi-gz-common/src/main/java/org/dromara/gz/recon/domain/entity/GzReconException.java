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
import java.time.LocalDateTime;

/**
 * gz_recon_exception — 对账异常 entity（GZ-ADMIN-105）。
 *
 * <p>字段口径权威：doc/11 §9.3。业务流权威：doc/10 §10.N6（异常处理）。</p>
 *
 * <p><b>V1.1 建表 + 留 hook</b>：mock 模式 channel 侧无真实账单数据，跑批不主动写异常；
 * PAY 拉真实 fundflowbill 后由比对逻辑写入（exception_type ∈ gmv_diff / refund_missing /
 * fee_diff / transaction_orphan / refund_orphan）。本 entity 供后续扩展，V1.1 仅建表不写。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-ADMIN-105)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("gz_recon_exception")
public class GzReconException extends TenantEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 异常发生日 */
    private LocalDate businessDay;

    /** preorder / gacha */
    private String businessType;

    /** gmv_diff / refund_missing / fee_diff / transaction_orphan / refund_orphan */
    private String exceptionType;

    /** 关联具体支付订单（admin 跳转详情） */
    private String transactionId;

    /** 异常详细数据（系统侧 vs 通道侧 diff，JSON 字符串） */
    private String detailJson;

    /** 0=未处理 / 1=已处理 */
    private Integer handled;

    /** 处理人 */
    private String handledBy;

    /** 处理时间 */
    private LocalDateTime handledTime;

    /** 处理备注 */
    private String handledNote;

    /** 备注 */
    private String remark;

    /** 软删（0=正常 / 2=删除） */
    @TableLogic
    private String delFlag;
}
