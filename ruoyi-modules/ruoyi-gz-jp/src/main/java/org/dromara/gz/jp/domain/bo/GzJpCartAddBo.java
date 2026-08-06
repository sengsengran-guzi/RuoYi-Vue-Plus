package org.dromara.gz.jp.domain.bo;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 加入购物车入参（GZ-JP-104，{@code POST /app/gz/jp/cart}）。
 *
 * <p>{@code userId} <b>不在入参里</b> —— 一律取当前登录态（{@code LoginHelper.getUserId()}），
 * 让调用方传 userId 等于把别人的购物车开放给任何人写。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-JP-104)
 */
@Data
public class GzJpCartAddBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 商品主键（mp 从商品详情 / 列表 VO 的 id 拿；string 传过来 Spring 自动转 Long） */
    @NotNull(message = "请选择商品")
    private Long productId;

    /**
     * 本次加入的数量（缺省 1）。
     *
     * <p>上限 99 是<b>单次加入量</b>的限制；累加后总量是否超限由 service 再校验一次
     * （98 件的车里再加 5 件 → 报「该商品数量已达上限」而不是静默截断）。</p>
     */
    @Min(value = 1, message = "数量至少为 1")
    @Max(value = 99, message = "单款商品一次最多加入 99 件")
    private Integer qty = 1;
}
