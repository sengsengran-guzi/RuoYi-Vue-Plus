package org.dromara.gz.ord.domain.dto.applet;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * mp 预购下单入参（GZ-ORD-104 AC2，POST /app/gz/ord/order/submit）。
 *
 * <p>点确认页「提交订单」时携带 productId + skuId + qty + addressId（+ 可选 userNote）。
 * user_id 从 sa-token 会话取（不信任前端传）。amount 后端按 sku_snapshot.price_cent × qty 重算
 * （不信任前端金额，防篡改，doc/11 §6.3 计算口径）。</p>
 *
 * <p>ID 跨层契约（CLAUDE.md 跨层契约 #1）：前端传 string id，落 Long 由 Jackson 自动 parse。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-ORD-104)
 */
@Data
public class SubmitOrderReq implements Serializable {

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
    private Integer qty;

    /** 收货地址主键（gz_user_address.id，校验归属当前用户） */
    @NotNull(message = "收货地址不能为空")
    private Long addressId;

    /** 用户下单备注（可选） */
    @Size(max = 255, message = "备注不超过 255 字")
    private String userNote;
}
