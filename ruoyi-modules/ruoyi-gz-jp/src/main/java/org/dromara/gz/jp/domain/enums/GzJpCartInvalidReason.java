package org.dromara.gz.jp.domain.enums;

import lombok.Getter;

/**
 * 购物车项失效原因（GZ-JP-104，UI:mp.cart「失效项置灰并标『已失效』，不计入合计、不可勾选」）。
 *
 * <p><b>读时惰性判定，不落库</b>：每次 {@code GET /app/gz/jp/cart/list} 实时算。
 * 场重新开起来 / 商品重新上架时，购物车项自动恢复可用 —— 没有需要清理的脏标记。</p>
 *
 * <p><b>判定优先级</b>（从「更根本」到「更可恢复」，只报第一条命中的）：
 * {@link #PRODUCT_REMOVED} → {@link #PRODUCT_OFF_SHELF} → {@link #EVENT_CLOSED}。
 * 商品都没了就没必要再说场怎么样。</p>
 *
 * <p><b>文案刻意不区分「商品被删」与「商品下架」</b>：对客人是同一件事（买不到了），
 * 且不向 C 端泄漏后台删除动作。两个 code 分开只为服务端排障与 GZ-JP-203 的埋点。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-JP-104)
 */
@Getter
public enum GzJpCartInvalidReason {

    /** 商品已被删除（{@code gz_jp_product} 查不到 —— 已软删或 id 失效） */
    PRODUCT_REMOVED("product_removed", "商品已下架"),

    /** 商品已下架（{@code status = off_shelf}） */
    PRODUCT_OFF_SHELF("product_off_shelf", "商品已下架"),

    /**
     * 所属场已结束 —— 店员手动关场，或 {@code end_time} 已过（读时惰性判定）。
     *
     * <p>判定收口在 {@code IGzJpEventService.isBookable}（严格闸，写路径口径），
     * <b>不是</b> {@code isVisible}（宽闸，只读浏览口径）：能浏览 ≠ 能下单。</p>
     */
    EVENT_CLOSED("event_closed", "本场已结束");

    /** 下发给前端的稳定 code（GZ-JP-203 据此埋点 / 做差异化提示） */
    private final String code;

    /** 直接可展示的中文文案（前端不必自己维护一份 code → 文案映射） */
    private final String text;

    GzJpCartInvalidReason(String code, String text) {
        this.code = code;
        this.text = text;
    }
}
