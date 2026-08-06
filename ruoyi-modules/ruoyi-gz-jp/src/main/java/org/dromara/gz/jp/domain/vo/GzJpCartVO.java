package org.dromara.gz.jp.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 购物车整车 VO（GZ-JP-104，{@code GET /app/gz/jp/cart/list}）。
 *
 * <p>UI:mp.cart = 按场分组的列表 + 底部固定条（全选 / 合计 / 去结算）。
 * {@link #groups} 喂列表，其余几个数喂底部条与角标。</p>
 *
 * <p><b>★ 底部只有「商品合计」一行，没有运费行</b>（REQ-ORDER-004 全包邮，UI:mp.cart.summary 明示）。
 * 本 VO 因此<b>没有</b> freight / shippingFee 之类字段 —— 不给前端「顺手加一行」的机会。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-JP-104)
 */
@Data
public class GzJpCartVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 按场分组的购物车（可下单的场在前，已结束的场在后，孤儿组「已失效商品」最后）。
     *
     * <p>空车时为空数组（不是 null）。</p>
     */
    private List<GzJpCartGroupVO> groups;

    /** 车里的商品款数（= 行数，含失效项）—— tabbar 角标用这个，不用件数 */
    private Integer itemCount;

    /** 失效款数（含在 {@link #itemCount} 里）；> 0 时前端可提示「N 件商品已失效」 */
    private Integer invalidCount;

    /** <b>有效</b>项的总件数（Σ qty，失效项不计）—— 底部「全选」旁的件数 */
    private Integer validQty;

    /**
     * ★ <b>有效</b>项的合计金额（<b>分</b>，Σ 单价×数量，<b>失效项不计入</b>）。
     *
     * <p>这是「全选状态下的合计」。用户实际勾选后的金额由前端按勾选项自行累加
     * （{@code GzJpCartItemVO.amountCent} 已算好），后端不维护勾选态 —— 勾选是纯前端交互，
     * 落库只会带来「勾选态与商品状态不一致」的脏数据。</p>
     *
     * <p>下单时金额<b>由后端重算</b>（GZ-JP-105），本字段只服务展示。</p>
     */
    private Long validAmountCent;
}
