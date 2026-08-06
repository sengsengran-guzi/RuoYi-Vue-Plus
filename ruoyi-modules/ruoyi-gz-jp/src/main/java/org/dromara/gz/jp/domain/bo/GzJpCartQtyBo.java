package org.dromara.gz.jp.domain.bo;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 修改购物车项数量入参（GZ-JP-104，{@code PUT /app/gz/jp/cart}）。
 *
 * <p>语义是<b>绝对赋值</b>（stepper 当前值），不是增量 —— 增量语义在弱网重发下会翻倍。</p>
 *
 * <p>{@code qty} 下限 1：数量减到 0 请走 {@code DELETE}，
 * 不用「qty=0 即删除」这种隐式约定（前端容易在 clamp 逻辑里误发 0 把商品删了）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-JP-104)
 */
@Data
public class GzJpCartQtyBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 购物车项主键（不是 productId —— 车里一行的身份） */
    @NotNull(message = "购物车项 ID 不能为空")
    private Long id;

    /** 目标数量（绝对值，1..99；减到 0 请走 DELETE） */
    @NotNull(message = "数量不能为空")
    @Min(value = 1, message = "数量至少为 1，如需移除请删除该商品")
    @Max(value = 99, message = "单款商品最多 99 件")
    private Integer qty;
}
