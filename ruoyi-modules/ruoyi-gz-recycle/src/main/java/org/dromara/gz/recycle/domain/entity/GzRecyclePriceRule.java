package org.dromara.gz.recycle.domain.entity;

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

/**
 * gz_recycle_price_rule — 回收价目表 entity（GZ-RECYCLE-001）。
 *
 * <p>字段口径权威：doc/11 §12.1 + §1 全局公共字段。按品类 + 数量区间配单价与匹配时长；
 * 估价时按 {@code (category, total_qty)} 命中区间（{@code qty_min <= total_qty AND
 * (qty_max IS NULL OR total_qty <= qty_max)}），估价 {@code = unit_price_cent × total_qty}。</p>
 *
 * <p><b>关键字段语义</b>：</p>
 * <ul>
 *   <li>{@code category} — 回收品类（字典 gz_recycle_category，甲方维护，附录 A.21）；UNIQUE(tenant_id, category, qty_min)</li>
 *   <li>{@code qtyMin} / {@code qtyMax} — 数量区间下界（含）/ 上界（含；NULL = 无上界）；同 category 下各区间不得重叠（业务层校验）</li>
 *   <li>{@code unitPriceCent} — 该区间单价（分/件）；估价 estimated = unitPriceCent × totalQty</li>
 *   <li>{@code durationMinutes} — 按数量匹配的核对/服务时长（分钟）；命中区间冻结进预约单 matched_duration_minutes</li>
 *   <li>{@code enabled} — 0=停用 / 1=启用；估价仅命中启用规则</li>
 * </ul>
 *
 * @author kevin-coder (sensenran-guzi · GZ-RECYCLE-001)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("gz_recycle_price_rule")
public class GzRecyclePriceRule extends TenantEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键（DB AUTO_INCREMENT） */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 回收品类（字典 gz_recycle_category，附录 A.21）— UNIQUE(tenant_id, category, qty_min) */
    private String category;

    /** 数量区间下界（含） */
    private Integer qtyMin;

    /** 数量区间上界（含；NULL = 无上界） */
    private Integer qtyMax;

    /** 该区间单价（分/件）；估价 estimated = unitPriceCent × totalQty */
    private Long unitPriceCent;

    /** 按数量匹配的核对/服务时长（分钟）；命中区间冻结进预约单 */
    private Integer durationMinutes;

    /** 启用标志（0=停用 / 1=启用）；估价仅命中启用规则 */
    private Integer enabled;

    /** 同品类内排序 */
    private Integer sortNo;

    /** 备注（公共字段，ruoyi 各 entity 显式定义） */
    private String remark;

    /** 软删标志（0=正常 / 1=删除；本项目 logicDeleteValue=1，@TableLogic 走全局） */
    @TableLogic
    private String delFlag;
}
