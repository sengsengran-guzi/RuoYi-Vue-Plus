package org.dromara.gz.recycle.service.impl;

import org.dromara.common.core.exception.ServiceException;
import org.dromara.gz.recycle.domain.bo.GzRecyclePriceRuleBo;
import org.dromara.gz.recycle.domain.entity.GzRecyclePriceRule;
import org.dromara.gz.recycle.mapper.GzRecyclePriceRuleMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * {@link GzRecyclePriceRuleServiceImpl} 区间不重叠校验单测（GZ-RECYCLE-001 AC 2/6）。
 *
 * <p>覆盖：重叠拒绝 / 相邻不重叠通过 / qtyMax NULL 无上界 / qtyMax &lt; qtyMin 拒绝 / 编辑排除自身。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-RECYCLE-001)
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class GzRecyclePriceRuleOverlapTest {

    @Mock
    private GzRecyclePriceRuleMapper baseMapper;

    private GzRecyclePriceRuleServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new GzRecyclePriceRuleServiceImpl(baseMapper);
    }

    private GzRecyclePriceRuleBo bo(String category, int qtyMin, Integer qtyMax) {
        GzRecyclePriceRuleBo b = new GzRecyclePriceRuleBo();
        b.setCategory(category);
        b.setQtyMin(qtyMin);
        b.setQtyMax(qtyMax);
        b.setUnitPriceCent(500L);
        b.setDurationMinutes(30);
        return b;
    }

    private GzRecyclePriceRule rule(long id, String category, int qtyMin, Integer qtyMax) {
        GzRecyclePriceRule r = new GzRecyclePriceRule();
        r.setId(id);
        r.setCategory(category);
        r.setQtyMin(qtyMin);
        r.setQtyMax(qtyMax);
        return r;
    }

    @Test
    @DisplayName("新建：同品类区间重叠 [3,8] vs 现有 [1,5] → 拒绝")
    void insert_rejectOverlap() {
        when(baseMapper.selectList(any())).thenReturn(List.of(rule(1L, "card", 1, 5)));
        ServiceException ex = assertThrows(ServiceException.class, () -> service.insertByBo(bo("card", 3, 8)));
        assertTrue(ex.getMessage().contains("重叠"));
    }

    @Test
    @DisplayName("新建：相邻区间 [6,10] vs 现有 [1,5] → 不重叠，通过")
    void insert_adjacentPasses() {
        when(baseMapper.selectList(any())).thenReturn(List.of(rule(1L, "card", 1, 5)));
        when(baseMapper.insert(any(GzRecyclePriceRule.class))).thenAnswer(inv -> {
            ((GzRecyclePriceRule) inv.getArgument(0)).setId(2001L);
            return 1;
        });
        Long id = service.insertByBo(bo("card", 6, 10));
        assertEquals(2001L, id);
    }

    @Test
    @DisplayName("新建：上界 NULL [10,∞] vs 现有 [1,5] → 不重叠，通过")
    void insert_nullUpperNoOverlap() {
        when(baseMapper.selectList(any())).thenReturn(List.of(rule(1L, "card", 1, 5)));
        when(baseMapper.insert(any(GzRecyclePriceRule.class))).thenAnswer(inv -> {
            ((GzRecyclePriceRule) inv.getArgument(0)).setId(2002L);
            return 1;
        });
        Long id = service.insertByBo(bo("card", 10, null));
        assertEquals(2002L, id);
    }

    @Test
    @DisplayName("新建：上界 NULL [3,∞] vs 现有 [1,5] → 重叠（3<=5），拒绝")
    void insert_nullUpperOverlaps() {
        when(baseMapper.selectList(any())).thenReturn(List.of(rule(1L, "card", 1, 5)));
        assertThrows(ServiceException.class, () -> service.insertByBo(bo("card", 3, null)));
    }

    @Test
    @DisplayName("新建：现有上界 NULL [5,∞]，新 [1,4] → 不重叠，通过；新 [1,6] → 重叠拒绝")
    void insert_existingNullUpper() {
        when(baseMapper.selectList(any())).thenReturn(List.of(rule(1L, "card", 5, null)));
        when(baseMapper.insert(any(GzRecyclePriceRule.class))).thenAnswer(inv -> {
            ((GzRecyclePriceRule) inv.getArgument(0)).setId(2003L);
            return 1;
        });
        assertEquals(2003L, service.insertByBo(bo("card", 1, 4)));
        assertThrows(ServiceException.class, () -> service.insertByBo(bo("card", 1, 6)));
    }

    @Test
    @DisplayName("新建：qtyMax < qtyMin [8,3] → 拒绝（区间非法）")
    void insert_rejectInvalidRange() {
        ServiceException ex = assertThrows(ServiceException.class, () -> service.insertByBo(bo("card", 8, 3)));
        assertTrue(ex.getMessage().contains("不能小于下界"));
    }

    @Test
    @DisplayName("新建：不同品类同区间 [1,5] doll vs card[1,5] → 不重叠（品类隔离），通过")
    void insert_differentCategoryNoOverlap() {
        // assertNoOverlap 查询按 category 过滤，doll 查不到 card 规则 → 空列表
        when(baseMapper.selectList(any())).thenReturn(List.of());
        when(baseMapper.insert(any(GzRecyclePriceRule.class))).thenAnswer(inv -> {
            ((GzRecyclePriceRule) inv.getArgument(0)).setId(2004L);
            return 1;
        });
        assertEquals(2004L, service.insertByBo(bo("doll", 1, 5)));
    }

    @Test
    @DisplayName("编辑：排除自身 id，改自己区间不算与自己重叠")
    void update_excludeSelf() {
        GzRecyclePriceRule self = rule(1L, "card", 1, 5);
        when(baseMapper.selectById(1L)).thenReturn(self);
        // selectList 已排除自身（service ne 条件），mock 返回空 = 无其他规则
        when(baseMapper.selectList(any())).thenReturn(List.of());
        when(baseMapper.updateById(any(GzRecyclePriceRule.class))).thenReturn(1);
        GzRecyclePriceRuleBo edit = bo("card", 1, 8);
        edit.setId(1L);
        assertTrue(service.updateByBo(edit));
    }
}
