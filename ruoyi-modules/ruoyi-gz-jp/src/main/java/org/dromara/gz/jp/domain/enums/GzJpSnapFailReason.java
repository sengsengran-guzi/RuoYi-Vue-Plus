package org.dromara.gz.jp.domain.enums;

/**
 * 代切失败原因 —— {@code purchase_status=failed} 或交易被取消 / 退货时写入，客人侧与 admin 都按它展示原因。
 *
 * <p>字段：{@code FIELD:gz_jp_snap_purchase.fail_reason}；取值与字典 {@code gz_jp_snap_fail_reason}（GZ-SNAP-101 迁移
 * {@code V202610050725}）一一对应，{@code GzJpSnapEnumDictTest} 守漂移。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-SNAP-102)
 */
public enum GzJpSnapFailReason {

    /** 已售出 */
    SOLD_OUT("sold_out", "已售出"),

    /** 已下架 */
    DELETED("deleted", "已下架"),

    /** 涨价 */
    PRICE_UP("price_up", "涨价"),

    /** 人工谈不成 */
    MANUAL_REJECTED("manual_rejected", "人工谈不成"),

    /** 卖家取消 */
    SELLER_CANCEL("seller_cancel", "卖家取消"),

    /** 验货不通过 */
    INSPECT_NG("inspect_ng", "验货不通过");

    private final String code;
    private final String label;

    GzJpSnapFailReason(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public String getCode() {
        return code;
    }

    public String getLabel() {
        return label;
    }

    /**
     * code → 中文文案。
     *
     * @param code 存库值（可能为 null）
     * @return 中文；null / 未知 code 原样返回
     */
    public static String labelOf(String code) {
        GzJpSnapFailReason v = of(code);
        return v == null ? code : v.label;
    }

    /**
     * code 是否为合法取值（如实回答，不静默回落）。
     *
     * @param code 待校验值
     * @return true = 合法
     */
    public static boolean isValid(String code) {
        return of(code) != null;
    }

    /**
     * code → 枚举。
     *
     * @param code 存库值
     * @return 对应枚举；null / 未知 code 返回 null
     */
    public static GzJpSnapFailReason of(String code) {
        for (GzJpSnapFailReason v : values()) {
            if (v.code.equals(code)) {
                return v;
            }
        }
        return null;
    }
}
