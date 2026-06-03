package org.dromara.gz.ord.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;

/**
 * mp 预购列表排序枚举（GZ-ORD-102 AC1 排序映射）。
 *
 * <p>三选一单排序（决策 D2，非多排序）。每个枚举绑定一段<b>白名单 ORDER BY 子句</b> —— 列名硬编码自
 * doc/11 §6.1，不含任何用户输入，故 {@link com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper#last(String)}
 * 拼接<b>无注入风险</b>。非法 {@code sortBy} 入参兜底 {@link #DEADLINE_ASC}（默认态，AC1）。</p>
 *
 * <ul>
 *   <li>{@link #ARRIVAL_ASC} 到货近 — 精确到货日升序（无精确日的 COALESCE 至 9999-12-31 排最后），同日按截止紧迫</li>
 *   <li>{@link #HOT} 热度 — 销量倒序（复用 §6.1 sales_count，决策 D1 不新增 view_count），同销量按截止紧迫</li>
 *   <li>{@link #DEADLINE_ASC} 截止紧迫（默认）— 截止时间升序</li>
 * </ul>
 *
 * @author kevin-coder (sensenran-guzi · GZ-ORD-102)
 */
@Getter
@AllArgsConstructor
public enum OrdProductSortEnum {

    /** 到货近：精确到货日升序（无精确日排最后），同日按截止紧迫 */
    ARRIVAL_ASC("arrival_asc",
        "COALESCE(delivery_date_exact, '9999-12-31') ASC, deadline_time ASC"),

    /** 热度：销量倒序（复用 sales_count），同销量按截止紧迫 */
    HOT("hot",
        "sales_count DESC, deadline_time ASC"),

    /** 截止紧迫（默认）：截止时间升序 */
    DEADLINE_ASC("deadline_asc",
        "deadline_time ASC");

    /** 前端传参 code */
    private final String code;

    /** 白名单 ORDER BY 子句（不含 "ORDER BY" 前缀；列名硬编码，无注入风险） */
    private final String orderByClause;

    /**
     * code → 枚举；非法 / 空 → 兜底 {@link #DEADLINE_ASC}（AC1 默认态）。
     *
     * @param code 前端 sortBy 参数（可空 / 非法）
     * @return 匹配枚举，未命中返回默认 {@link #DEADLINE_ASC}
     */
    public static OrdProductSortEnum ofCodeOrDefault(String code) {
        if (code == null || code.isBlank()) {
            return DEADLINE_ASC;
        }
        return Arrays.stream(values())
            .filter(e -> e.code.equals(code))
            .findFirst()
            .orElse(DEADLINE_ASC);
    }
}
