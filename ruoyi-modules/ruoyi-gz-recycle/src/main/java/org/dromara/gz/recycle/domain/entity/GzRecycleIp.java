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
 * gz_recycle_ip — 回收 IP 主数据 entity（GZ-RECYCLE-004，ADR-0012 §4 / 契约 15a §C.1）。
 *
 * <p>背景：回收物品「IP / 系列」原为 mp 前端硬编码 5 个不入库（火影/海贼王/航海王/鬼灭/初音），
 * 改为后台可配主数据：admin CRUD + mp 拉启用列表多选；用户仍可自由添加自定义 IP（不在列表的走自由文本，
 * 与列表项并存提交，自定义 IP 不回写主数据避免脏数据）。IP 纯标注、不进估价。</p>
 *
 * <p><b>关键字段语义</b>：</p>
 * <ul>
 *   <li>{@code ipName} — IP / 系列名称（火影 / 海贼王...）；UNIQUE(tenant_id, ip_name)（强约束 #3 唯一含 tenant_id）</li>
 *   <li>{@code enabled} — 0=停用 / 1=启用；mp 仅拉启用项做多选建议</li>
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
@TableName("gz_recycle_ip")
public class GzRecycleIp extends TenantEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键（DB AUTO_INCREMENT） */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** IP / 系列名称（火影 / 海贼王...）— UNIQUE(tenant_id, ip_name) */
    private String ipName;

    /** 启用标志（0=停用 / 1=启用）；mp 仅拉启用项 */
    private Integer enabled;

    /** 展示排序（小在前） */
    private Integer sortNo;

    /** 备注（公共字段，ruoyi 各 entity 显式定义） */
    private String remark;

    /** 软删标志（0=正常 / 1=删除；本项目 logicDeleteValue=1，@TableLogic 走全局） */
    @TableLogic
    private String delFlag;
}
