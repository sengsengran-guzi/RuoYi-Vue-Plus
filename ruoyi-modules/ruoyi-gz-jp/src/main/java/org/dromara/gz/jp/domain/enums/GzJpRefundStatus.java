package org.dromara.gz.jp.domain.enums;

/**
 * 行级退款状态（FIELD:gz_jp_order_item.refund_status，字典 {@code gz_jp_refund_status}）。
 *
 * <p><b>仅 {@code fulfill_status=purchase_failed} 的行会有值</b>，其余行恒 NULL。
 * ★ 退款金额恒 = <b>该行</b> {@code amount_cent}，不是整单（FLOW:F-JP-04.step2）。</p>
 *
 * <p><b>本卡（105）只建列 + 定义取值</b>；发起退款 / 回调推进 / 订单状态 rollup 归 <b>GZ-JP-107</b>
 * （走 jp 域独立退款入口，只共用通道层 {@code IWechatPayClient.refund}，
 * 不碰拼豆与回收在用的 {@code PayRefundServiceImpl} 线上资金链路）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-JP-105)
 */
public enum GzJpRefundStatus {

    /** 退款中 —— 已向微信发起，等回调。 */
    REFUNDING("refunding", "退款中"),

    /** 已退款 —— refund_amount_cent 恒 = 该行 amount_cent。 */
    REFUNDED("refunded", "已退款"),

    /** 退款失败 —— ★ 必须落库 + admin 可见，不静默吞。 */
    REFUND_FAILED("refund_failed", "退款失败");

    private final String code;
    private final String label;

    GzJpRefundStatus(String code, String label) {
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
     * code → 中文文案（mp 购买失败组「已退款 ¥xx」用）。
     *
     * @param code 存库值（可能为 null）
     * @return 中文；null / 未知 code 原样返回
     */
    public static String labelOf(String code) {
        for (GzJpRefundStatus s : values()) {
            if (s.code.equals(code)) {
                return s.label;
            }
        }
        return code;
    }
}
