package org.dromara.gz.jp.domain.enums;

/**
 * 订单行来源（FIELD:gz_jp_order_item.source，字典 {@code gz_jp_item_source}）。
 *
 * <p><b>★ 一期只有 {@link #BATCH} 一个值</b>，全部订单行恒为它。存在的唯一意义是给二期
 * 「代切」预留维度（REQ-SNAP-004「后面的部分用同一个系统」）—— 二期<b>只加一个枚举值
 * {@code snap} + 一条字典数据</b>，表结构 / 索引 / 已有数据一律不动。</p>
 *
 * <p>没有这一列的话，二期代切要么另建一套订单表（履约看板得查两张表），
 * 要么给 30 万行历史数据做 ALTER + 回填。一列 16 字节买的是这个。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-JP-105)
 */
public enum GzJpItemSource {

    /** 拼团（一期唯一来源）。 */
    BATCH("batch", "拼团");

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
}
