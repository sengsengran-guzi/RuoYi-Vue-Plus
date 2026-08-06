package org.dromara.gz.jp.domain.bo;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 提交订单入参（GZ-JP-105，FLOW:F-JP-02.step4；mp 端 UI:mp.order_confirm）。
 *
 * <p><b>★ 没有金额字段是故意的</b>：金额一律后端按<b>当前</b>商品价重算
 * （{@code Σ 单价 × 数量}），前端传什么都不会被采信。{@link #expectedAmountCent} 是个例外，
 * 但它<b>只用于比对</b>——不一致就报错让客人重新确认，绝不会被当成成交价。</p>
 *
 * <p><b>★ 没有 userId 字段</b>：一律取登录态（{@code LoginHelper.getUserId()}）。
 * 让调用方传 userId = 把别人的车和地址开放给任何人。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-JP-105)
 */
@Data
public class GzJpOrderSubmitBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 勾选的<b>购物车项</b> id 列表（{@code gz_jp_cart_item.id}）。
     *
     * <p>服务端按这些 id 重新拉本人的购物车行，再逐行校验场 / 商品 / 价格 ——
     * 前端传来的商品 id、数量、价格一概不采信。</p>
     *
     * <p>上限 50 = 购物车款数上限（GZ-JP-104），传更多只可能是构造请求。</p>
     */
    @NotEmpty(message = "请选择要结算的商品")
    @Size(max = 50, message = "一次最多结算 50 款商品")
    private List<Long> cartItemIds;

    /** 收货地址 id（{@code gz_user_address.id}）—— 必选，且必须属于当前用户 */
    @NotNull(message = "请选择收货地址")
    private Long addressId;

    /** 客人备注（可空，最长 255） */
    @Size(max = 255, message = "备注最多 255 字")
    private String userNote;

    /**
     * 客人在确认页上看到的合计（分）—— <b>可选，只做一致性比对</b>。
     *
     * <p>传了就比：与后端重算结果不一致 → 报
     * {@code GzJpOrderErrorCode#PRICE_CHANGED}（店员刚好在这几秒里改了价 / 客人页面停留太久），
     * 让客人重新确认，绝不静默按新价扣钱。不传则跳过比对（后端价即成交价）。</p>
     *
     * <p>★ 它<b>不是</b>金额来源。「后端重算不信前端」的口径不因这个字段松动 ——
     * 单测 {@code GzJpOrderSubmitTest} 直接断言传假价时成交金额仍是后端算出来的那个。</p>
     */
    private Long expectedAmountCent;
}
