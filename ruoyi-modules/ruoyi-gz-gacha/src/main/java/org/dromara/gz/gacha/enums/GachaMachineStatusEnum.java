package org.dromara.gz.gacha.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;

/**
 * 扭蛋机状态枚举（doc/11 §7.1 status / ticket 强约束 #4）。
 *
 * <p><b>三态钉死</b>（禁 archived / 漏 auto_off）：</p>
 * <ul>
 *   <li>{@link #ON_SHELF} 上架 — owner 手动上架，mp 可见可抽</li>
 *   <li>{@link #OFF_SHELF} 下架 — owner 手动下架（默认初始态），mp 不可见</li>
 *   <li>{@link #AUTO_OFF} 自动下架 — <b>仅 GACHA-104 / cron 写入</b>（整机所有奖品售罄 或 到 offline_time
 *       → 系统自动下架）；admin 不可手动设此态（决策 D5）。运营保证补货，无用户侧退款。</li>
 * </ul>
 *
 * @author kevin-coder (sensenran-guzi · GZ-GACHA-101)
 */
@Getter
@AllArgsConstructor
public enum GachaMachineStatusEnum {

    /** 上架（mp 可见可抽） */
    ON_SHELF("on_shelf", "上架"),
    /** 下架（owner 手动 / 初始默认态） */
    OFF_SHELF("off_shelf", "下架"),
    /** 自动下架（仅 GACHA-104/cron 写入，admin 不可手动设） */
    AUTO_OFF("auto_off", "自动下架");

    /** 落库枚举值 */
    private final String code;

    /** 显示名 */
    private final String label;

    /**
     * admin 手动可切换的目标态白名单（仅 on_shelf ↔ off_shelf；auto_off 仅 GACHA-104/cron，决策 D5）。
     */
    public static boolean isManualTarget(String code) {
        return ON_SHELF.code.equals(code) || OFF_SHELF.code.equals(code);
    }

    /**
     * code 合法性（任意三态之一）。
     */
    public static boolean isValid(String code) {
        return Arrays.stream(values()).anyMatch(e -> e.code.equals(code));
    }
}
