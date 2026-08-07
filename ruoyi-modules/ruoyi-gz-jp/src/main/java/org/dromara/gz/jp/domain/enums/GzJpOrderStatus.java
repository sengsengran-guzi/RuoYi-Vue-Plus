package org.dromara.gz.jp.domain.enums;

/**
 * 拼团订单状态（FIELD:gz_jp_order.business_status，字典 {@code gz_jp_order_status}）。
 *
 * <p><b>★ 订单级只管钱</b>：这里没有「已发货 / 已完成」——货走到哪是<b>商品行</b>的
 * {@link GzJpFulfillStatus}（REQ-FULFILL-003：一单 30 款各自进度不同，订单背不动一个总状态）。</p>
 *
 * <pre>
 *   created ──支付回调──→ paid ──GZ-JP-107 行级退款 rollup──→ partial_refunded / refunded
 *      └──取消 / 超时关单──→ cancelled
 * </pre>
 *
 * @author kevin-coder (sensenran-guzi · GZ-JP-105)
 */
public enum GzJpOrderStatus {

    /** 待支付 —— 下单即此态；5 分钟内未付由 GZ-PAY 超时关单。 */
    CREATED("created", "待支付"),

    /** 已支付 —— 支付回调推进，同时全部商品行进入「购买中」。 */
    PAID("paid", "已支付"),

    /** 已取消 —— 未支付订单取消 / 超时关单。 */
    CANCELLED("cancelled", "已取消"),

    /** 部分退款 —— 有购买失败行但非全部（GZ-JP-107 rollup）。 */
    PARTIAL_REFUNDED("partial_refunded", "部分退款"),

    /** 已退款 —— 全部行购买失败（GZ-JP-107 rollup）。 */
    REFUNDED("refunded", "已退款");

    private final String code;
    private final String label;

    GzJpOrderStatus(String code, String label) {
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
     * code → 中文文案（mp 端直接展示，不让前端各自维护一份映射）。
     *
     * @param code 存库值
     * @return 中文；未知 code 原样返回（不抛错、不显示空白）
     */
    public static String labelOf(String code) {
        for (GzJpOrderStatus s : values()) {
            if (s.code.equals(code)) {
                return s.label;
            }
        }
        return code;
    }

    /**
     * code 是否是本枚举的合法取值（admin 筛选参数校验用，GZ-JP-109）。
     *
     * <p>非法筛选值必须报错而不是静默忽略：忽略会让结果集看起来「更多」而不是「更少」，
     * 店员会以为自己筛错了条件（同 {@code GzJpFulfillStatus.isValid} 的口径）。</p>
     *
     * @param code 待校验的状态值
     * @return true = 合法
     */
    public static boolean isValid(String code) {
        for (GzJpOrderStatus s : values()) {
            if (s.code.equals(code)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 是否已收到钱（paid 及其后的退款态都意味着「这单付过款」）。
     *
     * <p>履约看板 / 客人订单「进行中」判定用它 —— <b>created 的行虽然
     * {@code fulfill_status} 也是 purchasing（列默认值），但那是没付钱的单，不能拿去采购</b>。</p>
     *
     * @param code 订单状态存库值
     * @return true = 付过款
     */
    public static boolean isPaidLike(String code) {
        return PAID.code.equals(code) || PARTIAL_REFUNDED.code.equals(code) || REFUNDED.code.equals(code);
    }
}
