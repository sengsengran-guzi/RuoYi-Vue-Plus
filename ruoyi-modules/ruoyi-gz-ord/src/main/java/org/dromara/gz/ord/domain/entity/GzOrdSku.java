package org.dromara.gz.ord.domain.entity;

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
 * gz_ord_sku — 商品 SKU entity（GZ-ORD-101）。
 *
 * <p>字段口径权威：doc/11 §6.2 gz_ord_sku + §1 全局公共字段。</p>
 *
 * <p><b>关键字段语义</b>：</p>
 * <ul>
 *   <li>{@code priceCent} 单价分（禁 _fen）</li>
 *   <li>{@code stockTotal} / {@code stockRemain} 库存，<b>NULL = 无限</b>（不用 -1/999999，强约束 #3）</li>
 *   <li>{@code version} 乐观锁，库存扣减走 {@code UPDATE ... WHERE id=? AND version=?}（doc/10 §7.N6）</li>
 *   <li>{@code enabled} 被订单引用的 SKU 改 0 软停用而非物理删（决策 D4 / R3）</li>
 * </ul>
 *
 * <p>注：{@code @Version} 用于 admin 编辑路径的通用乐观锁；库存扣减 {@code tryDeductStock} 走 mapper
 * 自定义 SQL（需 {@code stock_remain IS NULL OR >= ?} 条件，超出 @Version 自动机制表达力，doc/11 §6.2）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-ORD-101)
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("gz_ord_sku")
public class GzOrdSku extends TenantEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键（DB AUTO_INCREMENT，不暴露前端） */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** FK 语义→gz_ord_product.id（不加 DB 外键，应用层保证） */
    private Long productId;

    /** 业务码 SKU-yyyyMMdd-6位序号 — UNIQUE(tenant_id, sku_no) */
    private String skuNo;

    /** 规格名（如「标准款」/「豪华版」；单规格商品也建一条 spec_name='标准款'，决策 D1） */
    private String specName;

    /** 单价（分）— 禁 _fen */
    private Long priceCent;

    /** 总库存（NULL = 无限） */
    private Integer stockTotal;

    /** 当前剩余（NULL = 无限；扣减/退款归还，doc/10 §7.E2） */
    private Integer stockRemain;

    /** 0停用/1启用（被订单引用的 SKU 软停用而非物理删） */
    private Integer enabled;

    /** 同商品内排序 */
    private Integer sortNo;

    /** 乐观锁（库存扣减用，doc/10 §7.N6） */
    @Version
    private Integer version;

    /** 备注（公共字段） */
    private String remark;

    /** 软删标志（0=正常 / 2=删除，对齐 ruoyi @TableLogic） */
    @TableLogic
    private String delFlag;
}
