package org.dromara.gz.jp.domain.enums;

/**
 * 代切下单任务状态 —— 「买没买到」这根轴（与 {@link GzJpSnapTradeStatus}「买到之后到评价」正交，ADR-0007 双状态机范式）。
 *
 * <pre>
 *   queued 待下单 → executing 下单中 → success 已买到
 *                                   ├→ failed 未买到
 *                                   └→ unknown 结果不明（对账中，满 3 轮转人工）
 *   manual_pending 待人工 → success / failed
 * </pre>
 *
 * <p>本枚举只定义取值；合法转移 / 抢任务的条件更新归调度与执行卡（GZ-SNAP-305 起）。</p>
 *
 * <p>字段：{@code FIELD:gz_jp_snap_purchase.purchase_status}；取值与字典 {@code gz_jp_snap_purchase_status}（GZ-SNAP-101 迁移
 * {@code V202610050725}）一一对应，{@code GzJpSnapEnumDictTest} 守漂移。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-SNAP-102)
 */
public enum GzJpSnapPurchaseStatus {

    /** 待下单 */
    QUEUED("queued", "待下单"),

    /** 下单中 */
    EXECUTING("executing", "下单中"),

    /** 结果不明 */
    UNKNOWN("unknown", "结果不明"),

    /** 待人工 */
    MANUAL_PENDING("manual_pending", "待人工"),

    /** 已买到 */
    SUCCESS("success", "已买到"),

    /** 未买到 */
    FAILED("failed", "未买到");

    private final String code;
    private final String label;

    GzJpSnapPurchaseStatus(String code, String label) {
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
        GzJpSnapPurchaseStatus v = of(code);
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
    public static GzJpSnapPurchaseStatus of(String code) {
        for (GzJpSnapPurchaseStatus v : values()) {
            if (v.code.equals(code)) {
                return v;
            }
        }
        return null;
    }
}
