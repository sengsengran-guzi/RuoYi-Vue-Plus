package org.dromara.gz.coupon.strategy;

import org.dromara.common.core.exception.ServiceException;
import org.dromara.gz.coupon.domain.bo.CouponAudienceConditionDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CouponAudienceResolver} 单测（ADR-0010）：AND 交集 + 结构校验 + JSON 解析。
 *
 * @author kevin-coder (sensenran-guzi · ADR-0010)
 */
@Tag("dev")
class CouponAudienceResolverTest {

    /** 固定返回集的桩条件，隔离 resolver 自身逻辑（不依赖真实 DB 查询）。 */
    private record StubCond(String type, Set<Long> result) implements ICouponAudienceCondition {
        @Override
        public String type() {
            return type;
        }

        @Override
        public Set<Long> resolve(CouponAudienceConditionDto cond) {
            return result;
        }
    }

    private CouponAudienceResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new CouponAudienceResolver(List.of(
            new StubCond("register_time", Set.of(1L, 2L, 3L)),
            new StubCond("did_pindou", Set.of(2L, 3L, 4L)),
            new StubCond("phone_bound", Set.of(3L, 5L))));
    }

    private CouponAudienceConditionDto cond(String type, String start, String end, Boolean completedOnly) {
        CouponAudienceConditionDto c = new CouponAudienceConditionDto();
        c.setType(type);
        c.setStart(start);
        c.setEnd(end);
        c.setCompletedOnly(completedOnly);
        return c;
    }

    @Test
    @DisplayName("多条件 AND 交集：register_time ∩ did_pindou = {2,3}")
    void resolve_andIntersection() {
        Set<Long> r = resolver.resolveByConditions(List.of(
            cond("register_time", "2026-01-01", "2026-12-31", null),
            cond("did_pindou", null, null, true)));
        assertEquals(Set.of(2L, 3L), r);
    }

    @Test
    @DisplayName("三条件 AND 交集收敛到 {3}")
    void resolve_threeConditions() {
        Set<Long> r = resolver.resolveByConditions(List.of(
            cond("register_time", "2026-01-01", null, null),
            cond("did_pindou", null, null, null),
            cond("phone_bound", null, null, null)));
        assertEquals(Set.of(3L), r);
    }

    @Test
    @DisplayName("单条件直接返回该条件命中集")
    void resolve_singleCondition() {
        assertEquals(Set.of(3L, 5L), resolver.resolveByConditions(List.of(cond("phone_bound", null, null, null))));
    }

    @Test
    @DisplayName("空条件列表 → 抛异常（防零条件全量误发）")
    void resolve_emptyConditions_throws() {
        assertThrows(ServiceException.class, () -> resolver.resolveByConditions(List.of()));
        assertThrows(ServiceException.class, () -> resolver.resolveByConditions(null));
    }

    @Test
    @DisplayName("未知条件类型 → 抛异常")
    void resolve_unknownType_throws() {
        ServiceException ex = assertThrows(ServiceException.class,
            () -> resolver.resolveByConditions(List.of(cond("not_exist", null, null, null))));
        assertTrue(ex.getMessage().contains("不支持的条件类型"));
    }

    @Test
    @DisplayName("register_time 无任何日期 → 抛异常")
    void resolve_registerTimeNoDates_throws() {
        assertThrows(ServiceException.class,
            () -> resolver.resolveByConditions(List.of(cond("register_time", null, null, null))));
    }

    @Test
    @DisplayName("parseConfig 解析合法 issue_config_json")
    void parseConfig_valid() {
        List<CouponAudienceConditionDto> conds =
            resolver.parseConfig("{\"conditions\":[{\"type\":\"phone_bound\"},{\"type\":\"did_pindou\",\"completedOnly\":true}]}");
        assertEquals(2, conds.size());
        assertEquals("phone_bound", conds.get(0).getType());
        assertEquals(Boolean.TRUE, conds.get(1).getCompletedOnly());
    }

    @Test
    @DisplayName("parseConfig 空配置 → 抛异常")
    void parseConfig_blank_throws() {
        assertThrows(ServiceException.class, () -> resolver.parseConfig(""));
        assertThrows(ServiceException.class, () -> resolver.parseConfig("{\"conditions\":[]}"));
    }
}
