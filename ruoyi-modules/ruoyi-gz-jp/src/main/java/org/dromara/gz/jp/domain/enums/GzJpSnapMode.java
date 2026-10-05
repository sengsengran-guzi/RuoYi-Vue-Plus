package org.dromara.gz.jp.domain.enums;

/**
 * 代切下单方式 —— 入队时定；风控暂停时 {@code auto} 可批量改 {@code manual}。
 *
 * <p>字段：{@code FIELD:gz_jp_snap_purchase.mode}；取值与字典 {@code gz_jp_snap_mode}（GZ-SNAP-101 迁移
 * {@code V202610050725}）一一对应，{@code GzJpSnapEnumDictTest} 守漂移。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-SNAP-102)
 */
public enum GzJpSnapMode {

    /** 自动 */
    AUTO("auto", "自动"),

    /** 人工 */
    MANUAL("manual", "人工");

    private final String code;
    private final String label;

    GzJpSnapMode(String code, String label) {
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
        GzJpSnapMode v = of(code);
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
    public static GzJpSnapMode of(String code) {
        for (GzJpSnapMode v : values()) {
            if (v.code.equals(code)) {
                return v;
            }
        }
        return null;
    }
}
