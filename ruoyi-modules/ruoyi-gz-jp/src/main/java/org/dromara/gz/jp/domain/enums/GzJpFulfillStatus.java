package org.dromara.gz.jp.domain.enums;

/**
 * 履约状态（FIELD:gz_jp_order_item.fulfill_status，字典 {@code gz_jp_fulfill_status}）。
 *
 * <p><b>★ 挂在商品行上，不是订单上</b>（REQ-FULFILL-003）：一单 30 款各自进度可以完全不同，
 * 「这单到哪了」这个问法在拼团里不成立。</p>
 *
 * <pre>
 *   purchasing 购买中
 *     ├─ purchase_failed 购买失败      ← 分支终态（退款 GZ-JP-107）
 *     └─ await_seller_ship 等待官方发货
 *          → jp_shipped 日本仓库已发货 → customs 清关中
 *          → cn_sorting 国内分拣中 → delivered 发货完毕（终态，必带运单号）
 * </pre>
 *
 * <p><b>★ 允许跳过中间态</b>（REQ-EVENT-002：线下现货没有「等待官方发货」这一步）。
 * 一期<b>不做回退</b>。状态机（合法转移判定 / 批量推进）归 <b>GZ-JP-106</b> ——
 * 本枚举只定义取值与顺序，<b>不要</b>在这里长出转移规则，否则 106 会出现第二份真源。</p>
 *
 * <p><b>本卡（105）只用到 {@link #PURCHASING}</b>：支付回调把订单全部行置为履约起点。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-JP-105)
 */
public enum GzJpFulfillStatus {

    /** 购买中 —— 支付成功后的履约起点（列默认值）。 */
    PURCHASING("purchasing", "购买中", 1),

    /** 购买失败 —— 分支终态，触发行级退款。甲方口径：这是常走的路，不是异常。 */
    PURCHASE_FAILED("purchase_failed", "购买失败", 1),

    /** 等待官方发货 —— 现货可跳过。 */
    AWAIT_SELLER_SHIP("await_seller_ship", "等待官方发货", 2),

    /** 日本仓库已发货。 */
    JP_SHIPPED("jp_shipped", "日本仓库已发货", 3),

    /** 清关中。 */
    CUSTOMS("customs", "清关中", 4),

    /** 国内分拣中。 */
    CN_SORTING("cn_sorting", "国内分拣中", 5),

    /** 发货完毕 —— 终态；置此态必须同时填 carrier_code + tracking_no。 */
    DELIVERED("delivered", "发货完毕", 6);

    private final String code;
    private final String label;
    /** 链上位次（GZ-JP-106 判「倒退」用；purchase_failed 与 purchasing 同级，是分支不是前进） */
    private final int step;

    GzJpFulfillStatus(String code, String label, int step) {
        this.code = code;
        this.label = label;
        this.step = step;
    }

    public String getCode() {
        return code;
    }

    public String getLabel() {
        return label;
    }

    public int getStep() {
        return step;
    }

    /**
     * code → 中文文案（mp 分组头 / admin 看板直接展示）。
     *
     * @param code 存库值
     * @return 中文；未知 code 原样返回
     */
    public static String labelOf(String code) {
        for (GzJpFulfillStatus s : values()) {
            if (s.code.equals(code)) {
                return s.label;
            }
        }
        return code;
    }

    /**
     * code 是否为合法履约状态（如实回答，不静默回落）。
     *
     * @param code 待校验值
     * @return true = 合法
     */
    public static boolean isValid(String code) {
        for (GzJpFulfillStatus s : values()) {
            if (s.code.equals(code)) {
                return true;
            }
        }
        return false;
    }
}
