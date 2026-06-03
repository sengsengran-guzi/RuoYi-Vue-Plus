package org.dromara.gz.ord.domain.vo.applet;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * mp 详情页 SKU 展示对象（GZ-ORD-103 AC1）。
 *
 * <p>字段口径权威：doc/11 §6.2 gz_ord_sku（展示子集）。{@code stockRemain} 为 {@code null} 表「无限库存」
 * （DB {@code stock_total IS NULL}，强约束 #3）—— 前端不显示库存数字、stepper 不设上限（决策 D3）。
 * {@code enabled=0} 的 SKU 仍返回（前端置灰不可选），保留 snapshot 语义。</p>
 *
 * <p>ID 跨层契约（CLAUDE.md 跨层契约 #1）：{@code id} 用 {@link ToStringSerializer} 转 string。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-ORD-103)
 */
@Data
public class OrdSkuMpVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** SKU 主键（string；前端选规格 / 跳确认页用） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** 规格名（如「标准款」/「豪华版」） */
    private String specName;

    /** 单价（分） */
    private Long priceCent;

    /** 当前剩余库存；{@code null} = 无限（前端不显示数字 / stepper 不设上限，决策 D3） */
    private Integer stockRemain;

    /** 是否可售 0=停用 / 1=启用（停用 SKU 前端置灰不可选） */
    private Integer enabled;
}
