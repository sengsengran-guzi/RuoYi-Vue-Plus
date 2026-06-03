package org.dromara.gz.ord.domain.bo;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.dromara.common.core.validate.AddGroup;
import org.dromara.common.core.validate.EditGroup;

import java.io.Serial;
import java.io.Serializable;

/**
 * 商品 SKU 增改业务对象（GZ-ORD-101，嵌在 {@link GzOrdProductBo} 的 SKU 列表内）。
 *
 * <p>字段口径权威：doc/11 §6.2。</p>
 *
 * <p><b>受控字段</b>（admin 可填）：specName / priceCent（分）/ stockTotal（NULL=无限）/ enabled /
 * sortNo。<b>系统管理字段</b>：skuNo（系统生成）/ stockRemain（新建 = stockTotal；扣减由 tryDeductStock）/
 * version / tenantId / 公共字段。</p>
 *
 * <p><b>id 跨层契约</b>：编辑时 SKU diff 用 id（string，JS 精度安全）；新增 SKU id 为空。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-ORD-101)
 */
@Data
public class GzOrdSkuBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** SKU 主键（编辑路径：非空 = 既有 SKU diff 更新 / 空 = 新增 SKU） */
    private Long id;

    /** 规格名（如「标准款」） */
    @NotBlank(message = "规格名不能为空", groups = {AddGroup.class, EditGroup.class})
    @Size(max = 64, message = "规格名长度不能超过 64", groups = {AddGroup.class, EditGroup.class})
    private String specName;

    /** 单价（分）— 禁 _fen；≥ 0 */
    @NotNull(message = "单价不能为空", groups = {AddGroup.class, EditGroup.class})
    @Min(value = 0, message = "单价不能为负", groups = {AddGroup.class, EditGroup.class})
    private Long priceCent;

    /** 总库存（NULL = 无限；非 NULL 时 ≥ 0） */
    @Min(value = 0, message = "库存不能为负", groups = {AddGroup.class, EditGroup.class})
    private Integer stockTotal;

    /** 0停用/1启用（不填默认 1） */
    private Integer enabled;

    /** 同商品内排序（不填默认 0） */
    private Integer sortNo;
}
