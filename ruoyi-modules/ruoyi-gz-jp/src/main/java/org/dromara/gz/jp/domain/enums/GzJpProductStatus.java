package org.dromara.gz.jp.domain.enums;

/**
 * 商品状态（FIELD:gz_jp_product.status，字典 {@code gz_jp_product_status}）。
 *
 * <p>两态：{@link #OFF_SHELF} 已下架（新建默认） / {@link #ON_SHELF} 已上架。</p>
 *
 * <p><b>★ 一期无库存概念</b>（集单预订本质不限量，甲方从未提过库存）——「卖完了」由店员手动下架表达，
 * 不要为此加 stock 列（见 field-ssot.yaml 的 gz_jp_product 段与 flows.yaml FLOW:F-JP-01 说明）。</p>
 *
 * <p><b>客人可见 ≠ on_shelf</b>：商品对客人可见需<b>同时</b>满足「商品 on_shelf」+「所属场生效状态 open」
 * （FLOW:F-JP-01.step2「商品 status=on_shelf；未开场时仍不可见」）。场侧判定统一走
 * {@link GzJpEventStatus#effective}，本枚举只管商品自身这一半。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-JP-102)
 */
public enum GzJpProductStatus {

    /** 已上架 —— 所属场 open 时客人可见可下单。 */
    ON_SHELF("on_shelf"),

    /** 已下架 —— 新建商品默认态，客人不可见。 */
    OFF_SHELF("off_shelf");

    private final String code;

    GzJpProductStatus(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    /**
     * code 解析为枚举。
     *
     * @param code 存库值（on_shelf / off_shelf）
     * @return 匹配的枚举；null / 未知值一律回落 {@link #OFF_SHELF}（最保守：客人不可见）
     */
    public static GzJpProductStatus of(String code) {
        for (GzJpProductStatus s : values()) {
            if (s.code.equals(code)) {
                return s;
            }
        }
        return OFF_SHELF;
    }

    /**
     * 判断 code 是否为本枚举已知的合法值（区别于 {@link #of}：of 会静默回落，这里如实回答）。
     *
     * @param code 待校验值
     * @return true = 合法
     */
    public static boolean isValid(String code) {
        for (GzJpProductStatus s : values()) {
            if (s.code.equals(code)) {
                return true;
            }
        }
        return false;
    }
}
