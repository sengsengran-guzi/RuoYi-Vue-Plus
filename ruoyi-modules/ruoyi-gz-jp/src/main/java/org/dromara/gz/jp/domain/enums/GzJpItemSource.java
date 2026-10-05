package org.dromara.gz.jp.domain.enums;

/**
 * 订单行来源（FIELD:gz_jp_order_item.source，字典 {@code gz_jp_item_source}）。
 *
 * <p>一期预留的这一维度，二期代切直接复用（ADR-0025 §5 / REQ-SNAP-004「后面的部分用同一个系统」）：
 * 订单表、退款表、履约状态机、履约看板仍是一套，代切行只是 {@code source=snap} 的订单行。</p>
 *
 * <p><b>★ 来源决定外键</b>（应用层不变量，DB 两列都可空，由
 * {@code GzJpOrderItemSourceInvariant} 在订单行写入口统一校验）：</p>
 * <ul>
 *   <li>{@link #BATCH} ⇔ {@code product_id} 非空 且 {@code snap_item_id} 为空；</li>
 *   <li>{@link #SNAP} ⇔ {@code snap_item_id} 非空 且 {@code product_id} 为空。</li>
 * </ul>
 *
 * @author kevin-coder (sensenran-guzi · GZ-JP-105 / GZ-SNAP-102)
 */
public enum GzJpItemSource {

    /** 拼团（一期来源，列默认值）。 */
    BATCH("batch", "拼团"),

    /** 代切（二期：煤炉单件代买，一单只有这一行，REQ-SNAP-035）。 */
    SNAP("snap", "代切");

    private final String code;
    private final String label;

    GzJpItemSource(String code, String label) {
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
     * code → 枚举。
     *
     * @param code 存库值
     * @return 对应枚举；null / 未知 code 返回 null（调用方按非法处理，不静默回落成 batch）
     */
    public static GzJpItemSource of(String code) {
        for (GzJpItemSource s : values()) {
            if (s.code.equals(code)) {
                return s;
            }
        }
        return null;
    }

    /**
     * 是否代切行。
     *
     * @param code 存库值
     * @return true = snap
     */
    public static boolean isSnap(String code) {
        return SNAP.code.equals(code);
    }
}
