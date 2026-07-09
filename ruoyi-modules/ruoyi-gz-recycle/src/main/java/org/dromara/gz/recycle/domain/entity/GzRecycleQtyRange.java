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
 * gz_recycle_qty_range — 回收数量桶 + 预计回收时长 entity（GZ-RECYCLE-004，ADR-0012 §3 / 契约 15a §C.5）。
 *
 * <p>背景：去估价后用户不再填精确件数，改选「数量桶」（1-25 / 25-50 / 50-75）；桶单选只驱动「预计回收时长」、
 * 不参与任何金额计算。一表同时承载「桶标签」+「预计时长」（admin 可配 duration_minutes、可加 75+ 档），
 * <b>非 sys_dict</b>（规避 ruoyi 系统字典 tenant 000000 + getDictLabel 坑）。</p>
 *
 * <p><b>关键字段语义</b>：</p>
 * <ul>
 *   <li>{@code code} — 桶机读码（mp 提交落 product_snapshot_json.qtyBucketCode，后端按 code 查时长）；UNIQUE(tenant_id, code)</li>
 *   <li>{@code label} — 桶展示文案（1-25 件 等）</li>
 *   <li>{@code durationMinutes} — 该桶预计回收时长（分钟），admin 可配；命中即落预约单 matched_duration_minutes</li>
 *   <li>{@code enabled} — 0=停用 / 1=启用；mp 仅拉启用桶</li>
 *   <li>{@code sortNo} — 展示排序（小在前）</li>
 * </ul>
 *
 * @author kevin-coder (sensenran-guzi · GZ-RECYCLE-004)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("gz_recycle_qty_range")
public class GzRecycleQtyRange extends TenantEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键（DB AUTO_INCREMENT） */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 桶机读码（mp 提交落 qtyBucketCode；后端按 code 查时长）— UNIQUE(tenant_id, code) */
    private String code;

    /** 桶展示文案（如 1-25 件） */
    private String label;

    /** 该桶预计回收时长（分钟），admin 可配；命中落预约单 matched_duration_minutes */
    private Integer durationMinutes;

    /** 大单占位：1=选此档下单额外整格占用下一个 enabled 到店时段（仅最高档；末档无下一档则不占）；0=普通档（GZ-RECYCLE-007） */
    private Integer occupyNextSlot;

    /** 启用标志（0=停用 / 1=启用）；mp 仅拉启用桶 */
    private Integer enabled;

    /** 展示排序（小在前） */
    private Integer sortNo;

    /** 备注（公共字段，ruoyi 各 entity 显式定义） */
    private String remark;

    /** 软删标志（0=正常 / 1=删除；本项目 logicDeleteValue=1，@TableLogic 走全局） */
    @TableLogic
    private String delFlag;
}
