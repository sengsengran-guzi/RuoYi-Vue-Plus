package org.dromara.gz.ord.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;

/**
 * 预购商品状态枚举（doc/11 §6.1 status / 强约束 #4）。
 *
 * <p><b>三态钉死</b>（禁 draft/on/off/archived）：</p>
 * <ul>
 *   <li>{@link #ON_SHELF} 上架 — admin 手动上架，mp 可见可下单</li>
 *   <li>{@link #OFF_SHELF} 下架 — admin 手动下架（默认初始态），mp 不可见</li>
 *   <li>{@link #AUTO_OFF} 自动下架 — <b>仅截止 cron 写入</b>（deadline_time 过 → on_shelf 转此态）；
 *       admin 不可手动设此态（决策 D5）</li>
 * </ul>
 *
 * @author kevin-coder (sensenran-guzi · GZ-ORD-101)
 */
@Getter
@AllArgsConstructor
public enum OrdProductStatusEnum {

    /** 上架（mp 可见可下单） */
    ON_SHELF("on_shelf", "上架"),
    /** 下架（admin 手动 / 初始默认态） */
    OFF_SHELF("off_shelf", "下架"),
    /** 自动下架（仅截止 cron 写入，admin 不可手动设） */
    AUTO_OFF("auto_off", "自动下架");

    /** 落库枚举值 */
    private final String code;

    /** 显示名 */
    private final String label;

    /**
     * admin 手动可切换的目标态白名单（仅 on_shelf ↔ off_shelf；auto_off 仅 cron，决策 D5）。
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
