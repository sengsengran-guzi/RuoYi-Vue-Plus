package org.dromara.gz.ord.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;

/**
 * 跨境物流状态枚举（C1 简化 2 态 + 终态，doc/10 §9 / doc/11 §6.4 / ADR-0005）。
 *
 * <p><b>C1 裁定钉死</b>（ticket 强约束 #5 / 风险 R7）：取消 v0.1 的 7 节点 / 批次推进，
 * 物流相关字段全内联在 gz_ord_order，<b>禁建 gz_logistics_node 表</b>：</p>
 * <ul>
 *   <li>{@link #IN_JAPAN} 在日本 — 订单默认初始物流态（下单即此态）</li>
 *   <li>{@link #IN_CHINA_DISPATCHING} 国内派送中 — admin 推进时录 cn_carrier_code + cn_tracking_no（留 D10）</li>
 *   <li>{@link #DELIVERED} 已签收 — 终态（用户确认 / 7 天自动签收 cron，留 D10）</li>
 * </ul>
 *
 * <p><b>与 business_status 并行独立</b>（doc/11 §6.3）：本卡建表即落默认 in_japan，
 * admin 推进 / 自动签收 cron 留 GZ-ADMIN-104（D10），本卡<b>不写物流值、不实现推进</b>。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-ORD-104)
 */
@Getter
@AllArgsConstructor
public enum LogisticsStatusEnum {

    /** 在日本（订单默认初始物流态） */
    IN_JAPAN("in_japan", "在日本"),
    /** 国内派送中（admin 推进录单号，留 D10） */
    IN_CHINA_DISPATCHING("in_china_dispatching", "国内派送中"),
    /** 已签收（终态，留 D10） */
    DELIVERED("delivered", "已签收");

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
