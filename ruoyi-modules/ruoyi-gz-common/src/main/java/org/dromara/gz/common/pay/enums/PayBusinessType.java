package org.dromara.gz.common.pay.enums;

/**
 * 支付业务类型枚举（doc/11 §4.2 / 附录 A.9）。
 *
 * <p>跨业务线：V1.0 仅 test 单；V1.1 接 preorder（业务线 A）/ gacha（业务线 B）。
 * pindou 预留（拼豆 V1.0/V1.1 不产生支付）。</p>
 *
 * <p>out_trade_no 业务前缀大写映射（doc/10 §6 Q6.3）：test → TEST- / preorder → PREORD- /
 * gacha → GACHA- / pindou → PINDOU-。</p>
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
            default -> throw new IllegalArgumentException("未知支付业务类型: " + businessType);
        };
    }
}
