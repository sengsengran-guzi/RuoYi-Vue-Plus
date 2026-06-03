package org.dromara.gz.ord.domain.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 商品 SKU 展示对象（GZ-ORD-101，嵌在 {@link GzOrdProductAdminVO} 的 SKU 列表内）。
 *
 * <p>字段口径权威：doc/11 §6.2。id / productId 用 {@code ToStringSerializer} 转 string（跨层契约 #1，
 * 防 JS long 精度丢失）。priceCent 分单位，前端除以 100 显示元。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-ORD-101)
 */
@Data
public class GzOrdSkuVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** SKU 主键（string） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** 所属商品 id（string） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long productId;

    /** 业务码 SKU-yyyyMMdd-6位序号 */
    private String skuNo;

    /** 规格名 */
    private String specName;

    /** 单价（分；前端 /100 显示元） */
    private Long priceCent;

    /** 总库存（NULL = 无限） */
    private Integer stockTotal;

    /** 当前剩余（NULL = 无限） */
    private Integer stockRemain;

    /** 0停用/1启用 */
    private Integer enabled;

    /** 同商品内排序 */
    private Integer sortNo;
}
