package org.dromara.gz.ord.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;

/**
 * 国内快递公司编码 → 中文名（GZ-ORD-105，C1 物流 cn_carrier_code 翻译）。
 *
 * <p>对齐字典 {@code gz_express_carrier}（9 项，doc/11 §6.4 / C1）：admin 录单号时选编码，
 * mp 详情物流卡 / admin 列表展示中文名。字典 seed 尚未落库时本枚举兜底翻译（in_china_dispatching
 * 后才有值，本卡查询场景已可用）。未知 code 原样回显。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-ORD-105)
 */
@Getter
@AllArgsConstructor
public enum ExpressCarrierEnum {

    YTO("yto", "圆通速递"),
    SF("sf", "顺丰速运"),
    ZTO("zto", "中通快递"),
    YUNDA("yunda", "韵达快递"),
    JD("jd", "京东物流"),
    EMS("ems", "EMS"),
    DEBANG("debang", "德邦快递"),
    JITU("jitu", "极兔速递"),
    OTHER("other", "其他快递");

    /** 落库编码 */
    private final String code;

    /** 中文名 */
    private final String label;

    /**
     * 编码 → 中文名（未知 / 空 → 原样回显 code，避免吞掉甲方录的非标编码）。
     *
     * @param code cn_carrier_code 落库值
     * @return 中文名；未知 → 原样 code；null → null
     */
    public static String labelOf(String code) {
        if (code == null || code.isBlank()) {
            return code;
        }
        return Arrays.stream(values())
            .filter(e -> e.code.equals(code))
            .map(ExpressCarrierEnum::getLabel)
            .findFirst()
            .orElse(code);
    }

    /**
     * code 是否为 9 项合法快递编码（GZ-ADMIN-104 进 in_china_dispatching 必录校验，doc/10 §9.N2）。
     *
     * @param code cn_carrier_code
     * @return 命中 9 项之一为 true
     */
    public static boolean isValid(String code) {
        if (code == null || code.isBlank()) {
            return false;
        }
        return Arrays.stream(values()).anyMatch(e -> e.code.equals(code));
    }
}
