package org.dromara.gz.ord.enums;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * OrdProductSortEnum 单测（GZ-ORD-102 AC7 ②）：排序 code → ORDER BY 子句映射（3 枚举各断言一次 + 非法兜底）。
 *
 * <p>排序 ORDER BY 子句是 mp 列表查询的安全核心（白名单拼接，决策 D2）；本测试钉死 3 个枚举的 SQL 片段
 * 与非法 / 空值兜底 DEADLINE_ASC，防止后续改动悄悄破坏排序语义或引入注入面。</p>
 */
@Tag("dev")
@DisplayName("OrdProductSortEnum — 排序 code → ORDER BY 映射 + 非法兜底")
class OrdProductSortEnumTest {

    @Test
    @DisplayName("arrival_asc → COALESCE 到货日升序（无精确日排最后），同日按截止")
    void arrivalAscClause() {
        OrdProductSortEnum e = OrdProductSortEnum.ofCodeOrDefault("arrival_asc");
        assertEquals(OrdProductSortEnum.ARRIVAL_ASC, e);
        assertEquals("COALESCE(delivery_date_exact, '9999-12-31') ASC, deadline_time ASC", e.getOrderByClause());
    }

    @Test
    @DisplayName("hot → 销量倒序（复用 sales_count），同销量按截止")
    void hotClause() {
        OrdProductSortEnum e = OrdProductSortEnum.ofCodeOrDefault("hot");
        assertEquals(OrdProductSortEnum.HOT, e);
        assertEquals("sales_count DESC, deadline_time ASC", e.getOrderByClause());
    }

    @Test
    @DisplayName("deadline_asc → 截止时间升序（默认态）")
    void deadlineAscClause() {
        OrdProductSortEnum e = OrdProductSortEnum.ofCodeOrDefault("deadline_asc");
        assertEquals(OrdProductSortEnum.DEADLINE_ASC, e);
        assertEquals("deadline_time ASC", e.getOrderByClause());
    }

    @Test
    @DisplayName("非法 / 空 / null → 兜底 DEADLINE_ASC（AC1 默认）")
    void illegalFallsBackToDeadline() {
        assertEquals(OrdProductSortEnum.DEADLINE_ASC, OrdProductSortEnum.ofCodeOrDefault("price_desc"));
        assertEquals(OrdProductSortEnum.DEADLINE_ASC, OrdProductSortEnum.ofCodeOrDefault(""));
        assertEquals(OrdProductSortEnum.DEADLINE_ASC, OrdProductSortEnum.ofCodeOrDefault("  "));
        assertEquals(OrdProductSortEnum.DEADLINE_ASC, OrdProductSortEnum.ofCodeOrDefault(null));
    }
}
