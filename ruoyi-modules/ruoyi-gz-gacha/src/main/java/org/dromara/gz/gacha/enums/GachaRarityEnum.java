package org.dromara.gz.gacha.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;

/**
 * 扭蛋稀有度枚举（doc/11 §7.2 rarity / 附录 A.10 / ticket 强约束 #2）。
 *
 * <p><b>四档钉死</b>（SSR/SR/R/N，不预留第五档）：<b>仅展示用，不影响抽奖事务</b>。
 * 字典 dict_type=gz_gacha_rarity（V202606171000 seed）；admin / mp 渲染稀有度 chip + 边框 + 光晕用。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-GACHA-101)
 */
@Getter
@AllArgsConstructor
public enum GachaRarityEnum {

    /** 最高稀有度 */
    SSR("SSR"),
    SR("SR"),
    R("R"),
    /** 普通 */
    N("N");

    /** 落库枚举值（与 dict_value 严格一致） */
    private final String code;

    /**
     * code 合法性（四档之一）。service 层落库前校验，非法拒绝（不静默吞）。
     */
    public static boolean isValid(String code) {
        return Arrays.stream(values()).anyMatch(e -> e.code.equals(code));
    }
}
