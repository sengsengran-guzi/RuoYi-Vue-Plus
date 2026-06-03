package org.dromara.gz.ord.domain.dto.applet;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * mp 下单前校验入参（GZ-ORD-103 AC3）。
 *
 * <p>点「立即购买」时携带 productId + skuId + quantity 给后端做即时 UX 校验（商品在售 + 未截止 +
 * SKU 可售 + 库存足）。最终扣减以 GZ-ORD-104 提交时乐观锁为准（双重防线，本校验只为即时反馈，决策 D1）。</p>
 *
 * <p>ID 跨层契约（CLAUDE.md 跨层契约 #1）：前端传 string id，落到 Long 字段由 Jackson 自动 parse。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-ORD-103)
 */
@Data
public class ValidatePurchaseReq implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 商品主键 */
    @NotNull(message = "商品 ID 不能为空")
    private Long productId;

    /** SKU 主键 */
    @NotNull(message = "规格 ID 不能为空")
    private Long skuId;

    /** 购买数量（≥ 1） */
    @NotNull(message = "购买数量不能为空")
    @Min(value = 1, message = "购买数量至少为 1")
    private Integer quantity;
}
