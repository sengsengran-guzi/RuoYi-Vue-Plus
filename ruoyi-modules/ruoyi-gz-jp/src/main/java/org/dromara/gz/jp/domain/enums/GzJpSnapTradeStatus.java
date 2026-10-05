package org.dromara.gz.jp.domain.enums;

/**
 * 代切交易状态 —— 煤炉下单成功之后到评价之前的那一段（FLOW:F-JP-09）。
 *
 * <p>{@code cancelled} 可从 {@code await_ship} 或 {@code shipped} 进入（卖家取消 / 已发货后丢件 / 事务局取消）；
 * {@code arrived} 之后只能走 {@code returned}（验货不通过退货）。本枚举只定义取值，转移归交易跟踪卡。</p>
 *
 * <p>字段：{@code FIELD:gz_jp_snap_purchase.trade_status}；取值与字典 {@code gz_jp_snap_trade_status}（GZ-SNAP-101 迁移
 * {@code V202610050725}）一一对应，{@code GzJpSnapEnumDictTest} 守漂移。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-SNAP-102)
 */
public enum GzJpSnapTradeStatus {

    /** 未成交 */
    NONE("none", "未成交"),

    /** 待卖家发货 */
    AWAIT_SHIP("await_ship", "待卖家发货"),

    /** 卖家已发货 */
    SHIPPED("shipped", "卖家已发货"),

    /** 已到仓 */
    ARRIVED("arrived", "已到仓"),

    /** 已评价 */
    RATED("rated", "已评价"),

    /** 已退货 */
    RETURNED("returned", "已退货"),

    /** 交易取消 */
    CANCELLED("cancelled", "交易取消");

    private final String code;
    private final String label;

    GzJpSnapTradeStatus(String code, String label) {
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
        GzJpSnapTradeStatus v = of(code);
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
    public static GzJpSnapTradeStatus of(String code) {
        for (GzJpSnapTradeStatus v : values()) {
            if (v.code.equals(code)) {
                return v;
            }
        }
        return null;
    }
}
