package org.dromara.gz.common.pay.enums;

/**
 * 支付业务类型枚举（doc/11 §4.2 / 附录 A.9）。
 *
 * <p>跨业务线：V1.0 仅 test 单；V1.1 接 preorder（业务线 A）/ gacha（业务线 B）。
 * pindou 预留（拼豆 V1.0/V1.1 不产生支付）。jp = 谷子宇宙拼团（独立小程序，GZ-JP-105）。</p>
 *
 * <p>out_trade_no 业务前缀大写映射（doc/10 §6 Q6.3）：test → TEST- / preorder → PREORD- /
 * gacha → GACHA- / pindou → PINDOU- / jp → JPO-。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-PAY-001)
 */
public final class PayBusinessType {

    /** V1.0 通道测试单 */
    public static final String TEST = "test";
    /** 预购（业务线 A，V1.1） */
    public static final String PREORDER = "preorder";
    /** 扭蛋（业务线 B，V1.1） */
    public static final String GACHA = "gacha";
    /** 拼豆（预留，V1.0/V1.1 不产生支付） */
    public static final String PINDOU = "pindou";
    /**
     * 谷子宇宙拼团 —— <b>另一个小程序</b>（appid 独立，GZ-SYS-022 多 appid 底座支撑）。
     *
     * <p>下单走 {@code gz_jp_order}，回调 SPI handler 在 ruoyi-gz-jp
     * （{@code JpPayCallbackHandler}）。business_order_no = {@code JPO-yyyyMMdd-6位} 订单号。</p>
     *
     * <p><b>★ 不进 4% 分成核算</b>：{@code GzReconBatchServiceImpl} / {@code GzReconDashboardServiceImpl}
     * 显式只算 preorder + gacha 两条业务线（合同 §4.1 口径）。要把拼团纳入分成属**商业口径变更**，
     * 须客户书面确认后再动对账域，不要在本枚举加值时"顺手"加进去。</p>
     */
    public static final String JP = "jp";

    private PayBusinessType() {
    }

    /**
     * 业务类型 → out_trade_no 前缀（doc/10 §6 Q6.3）。
     *
     * @param businessType 业务类型
     * @return 大写前缀（如 TEST / PREORD / GACHA / PINDOU）
     */
    public static String toOutTradePrefix(String businessType) {
        return switch (businessType) {
            case TEST -> "TEST";
            case PREORDER -> "PREORD";
            case GACHA -> "GACHA";
            case PINDOU -> "PINDOU";
            // 拼团：JPO = JP Order。gz_jp_order.order_no 与 out_trade_no 共用本前缀 + 同一日号段
            // （都由 PayOrderNoGenerator 发号，号只增不复用，不会互撞）。
            case JP -> "JPO";
            default -> throw new IllegalArgumentException("未知支付业务类型: " + businessType);
        };
    }
}
