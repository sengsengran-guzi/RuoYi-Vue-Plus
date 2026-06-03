package org.dromara.gz.ord.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;

/**
 * 预购订单业务态枚举（doc/10 §7 状态机 / doc/11 §6.3 business_status / 附录 A.5）。
 *
 * <p><b>状态机钉死</b>（ticket 强约束 #4）：</p>
 * <ul>
 *   <li>{@link #CREATED} 待支付 — N6 用户提交订单（初始态）</li>
 *   <li>{@link #PAID} 已支付 — N8 微信支付成功回调（SPI onPaid，仅 created 可达）</li>
 *   <li>{@link #CANCELLED} 已取消 — 用户主动取消 / 超时关单（<b>仅 created 可达</b>）</li>
 *   <li>{@link #IN_LOGISTICS} 物流中 — admin 推进进入跨境物流（留 D10，本卡不实现）</li>
 *   <li>{@link #DELIVERED} 已签收 — <b>即终态</b>，UI 显示「已完成」，不维护独立 closed（F6.4）</li>
 *   <li>{@link #REFUNDED} 已退款 — 走 PAY-103 退款回调 SPI（onRefunded）</li>
 * </ul>
 *
 * @author kevin-coder (sensenran-guzi · GZ-ORD-104)
 */
@Getter
@AllArgsConstructor
public enum OrdBusinessStatusEnum {

    /** 待支付（N6 提交订单初始态） */
    CREATED("created", "待支付"),
    /** 已支付（N8 支付成功回调，仅 created 可达） */
    PAID("paid", "已支付"),
    /** 已取消（用户取消 / 超时关单，仅 created 可达） */
    CANCELLED("cancelled", "已取消"),
    /** 物流中（admin 推进进入跨境物流，留 D10） */
    IN_LOGISTICS("in_logistics", "物流中"),
    /** 已签收（即终态，无独立 closed，F6.4） */
    DELIVERED("delivered", "已完成"),
    /** 已退款（走 PAY-103 退款回调 SPI） */
    REFUNDED("refunded", "已退款");

    /** 落库枚举值 */
    private final String code;

    /** 显示名 */
    private final String label;

    /**
     * code 合法性。
     */
    public static boolean isValid(String code) {
        return Arrays.stream(values()).anyMatch(e -> e.code.equals(code));
    }
}
