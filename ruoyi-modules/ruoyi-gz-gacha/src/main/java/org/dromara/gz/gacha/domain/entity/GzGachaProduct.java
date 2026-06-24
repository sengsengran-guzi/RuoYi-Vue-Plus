package org.dromara.gz.gacha.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;

import java.io.Serial;

/**
 * gz_gacha_product — 扭蛋产品库主表 entity（ADR-0013 / GZ-GACHA-112）。
 *
 * <p>产品 = 跨机器共享主数据，承载「产品固有属性」（名 / 图 / 参考价）。改一处、所有引用机器实时生效
 * （投放线 {@link GzGachaPrize} 走 FK 语义 join 取值）。</p>
 *
 * <p><b>字段铁律</b>（ADR-0013 §1 / CLAUDE.md）：tenant_id 拦截器注入；product_no 系统生成
 * （GPRD-yyyyMMdd-6位序号，UNIQUE(tenant_id, product_no)）；image_id FK 语义→gz_file_object.id（禁存裸 url）；
 * reference_value_cent 参考价（分，可空）；enabled 1可投放/0停用（停用不能再加入新机器，已投放线不受影响）；
 * 乐观锁 version；del_flag 仅 0/2（@TableLogic）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-GACHA-112)
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("gz_gacha_product")
public class GzGachaProduct extends TenantEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键（DB AUTO_INCREMENT，不暴露前端） */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 业务码 GPRD-yyyyMMdd-6位序号 — UNIQUE(tenant_id, product_no) */
    private String productNo;

    /** 产品名 */
    private String name;

    /** 产品图 FK 语义→gz_file_object.id（禁存裸 url；可空） */
    private Long imageId;

    /** 公示参考价（分，可空→不显示） */
    private Long referenceValueCent;

    /** IP 标签（产品库筛选用，可空） */
    private String ipTag;

    /** 1 可投放 / 0 停用（停用不能再加入新机器；已投放线不受影响） */
    private Integer enabled;

    /** 乐观锁 */
    @Version
    private Integer version;

    /** 备注（公共字段） */
    private String remark;

    /** 软删标志（0=正常 / 2=删除，对齐 ruoyi @TableLogic） */
    @TableLogic
    private String delFlag;
}
