package org.dromara.gz.common.pay.domain.bo;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 业务统一支付建单入参（GZ-PAY-101 AC 1）。
 *
 * <p>业务方（ORD-104 预购 / GACHA D09 扭蛋）在自己的下单事务内构造本 BO 调
 * {@code IGzPayTransactionService.createBusinessOrder} 创建 pending 支付交易并拿 mp 调起 5 参。</p>
 *
 * <p><b>分流真源</b>（强约束 #3）：{@code businessType} 落 {@code gz_pay_transaction.business_type}
 * —— 业务订单表<b>不加</b> business_line / biz_line / A-B 列。两业务线 4% 分成靠 business_type +
 * fee_cent 分别核算（doc/11 §4.6）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-PAY-101)
 */
@Data
@Builder
public class CreateOrderBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 业务类型（取 {@link org.dromara.gz.common.pay.enums.PayBusinessType} 常量）。
     *
     * <p>{@code preorder}（业务线 A）/ {@code gacha}（业务线 B）/ {@code test}（通道测试）。
     * 决定 out_trade_no 前缀 + 回调 SPI 路由 key。</p>
     */
    @NotBlank(message = "business_type 不能为空")
    private String businessType;

    /**
     * 业务订单业务码（如 gz_ord_order.order_no）。
     *
     * <p>落 {@code gz_pay_transaction.business_order_no}，SPI handler 据此定位自己的业务订单。
     * test 单可为 null（测试单无对应业务订单）。</p>
     */
    private String businessOrderNo;

    /** 金额（分）。业务侧建单时算好（预购总额 / 扭蛋单抽价等）。≥ 1 分。 */
    @NotNull(message = "amount_cent 不能为空")
    @Min(value = 1, message = "amount_cent 至少 1 分")
    private Long amountCent;

    /** 支付用户 openid（统一下单必需）。 */
    @NotBlank(message = "openid 不能为空")
    private String openid;

    /** 下单用户 id（FK → gz_user.id）。 */
    @NotNull(message = "userId 不能为空")
    private Long userId;

    /** 订单描述（传给微信统一下单 description，展示在用户支付页）。 */
    @NotBlank(message = "description 不能为空")
    private String description;
}
