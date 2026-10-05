package org.dromara.gz.jp.domain.enums;

/**
 * 退款单类型（二期新增列，唯一键 {@code uk_item_type(tenant_id, order_item_id, refund_type)}）。
 *
 * <p>★ 只有 {@code purchase_failed} 会写订单行 {@code refund_status / refund_amount_cent} 并参与订单
 * {@code business_status} rollup；{@code shipping_diff}（到付运费多退）发起与回调都不碰订单行
 * （FLOW:F-JP-09.step2）。按类型分支的退款逻辑归 GZ-SNAP-306，本卡只定义取值。</p>
 *
 * <p>字段：{@code FIELD:gz_jp_refund.refund_type}；取值与字典 {@code gz_jp_refund_type}（GZ-SNAP-101 迁移
 * {@code V202610050725}）一一对应，{@code GzJpSnapEnumDictTest} 守漂移。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-SNAP-102)
 */
public enum GzJpRefundType {

    /** 购买失败全额退 */
    PURCHASE_FAILED("purchase_failed", "购买失败全额退"),

    /** 到付运费多退 */
    SHIPPING_DIFF("shipping_diff", "到付运费多退");

    private final String code;
    private final String label;

    GzJpRefundType(String code, String label) {
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
        GzJpRefundType v = of(code);
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
    public static GzJpRefundType of(String code) {
        for (GzJpRefundType v : values()) {
            if (v.code.equals(code)) {
                return v;
            }
        }
        return null;
    }
}
