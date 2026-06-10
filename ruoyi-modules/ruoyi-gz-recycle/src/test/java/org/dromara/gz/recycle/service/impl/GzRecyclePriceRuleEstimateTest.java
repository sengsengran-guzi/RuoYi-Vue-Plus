package org.dromara.gz.recycle.service.impl;

import org.dromara.common.core.exception.ServiceException;
import org.dromara.gz.recycle.domain.entity.GzRecyclePriceRule;
import org.dromara.gz.recycle.domain.vo.GzRecycleEstimateVO;
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
 * {@link GzRecyclePriceRuleServiceImpl} 估价命中单测（GZ-RECYCLE-001 AC 3/6）。
 *
 * <p>覆盖：命中唯一（estimated = unitPrice × qty + 时长冻结）/ 命中 0 抛无报价 / 命中多抛配置错 /
 * 边界（qty=qtyMin / qty=qtyMax / 上界 NULL）/ 入参非法。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-RECYCLE-001)
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class GzRecyclePriceRuleEstimateTest {

    @Mock
    private GzRecyclePriceRuleMapper baseMapper;

    private GzRecyclePriceRuleServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new GzRecyclePriceRuleServiceImpl(baseMapper);
    }

    private GzRecyclePriceRule rule(long id, int qtyMin, Integer qtyMax, long unitPriceCent, int duration) {
        GzRecyclePriceRule r = new GzRecyclePriceRule();
        r.setId(id);
        r.setCategory("card");
        r.setQtyMin(qtyMin);
        r.setQtyMax(qtyMax);
        r.setUnitPriceCent(unitPriceCent);
        r.setDurationMinutes(duration);
        r.setEnabled(1);
        return r;
    }

    @Test
    @DisplayName("命中唯一：qty=3 落 [1,5] 单价 500/件 → estimated=1500 + 时长 30")
    void estimate_singleHit() {
        when(baseMapper.selectList(any())).thenReturn(List.of(
            rule(1L, 1, 5, 500L, 30),
            rule(2L, 6, 10, 400L, 60)));
        GzRecycleEstimateVO vo = service.estimate("card", 3);
        assertEquals(1L, vo.getRuleId());
        assertEquals(3, vo.getQty());
        assertEquals(500L, vo.getUnitPriceCent());
        assertEquals(1500L, vo.getEstimatedAmountCent());
        assertEquals(30, vo.getMatchedDurationMinutes());
    }

    @Test
    @DisplayName("命中边界：qty=qtyMax=5 → 命中 [1,5]（闭区间含上界）")
    void estimate_hitUpperBound() {
        when(baseMapper.selectList(any())).thenReturn(List.of(rule(1L, 1, 5, 500L, 30)));
        GzRecycleEstimateVO vo = service.estimate("card", 5);
        assertEquals(2500L, vo.getEstimatedAmountCent());
    }

    @Test
    @DisplayName("命中边界：qty=qtyMin=6 → 命中 [6,10]（闭区间含下界）")
    void estimate_hitLowerBound() {
        when(baseMapper.selectList(any())).thenReturn(List.of(
            rule(1L, 1, 5, 500L, 30),
            rule(2L, 6, 10, 400L, 60)));
        GzRecycleEstimateVO vo = service.estimate("card", 6);
        assertEquals(2L, vo.getRuleId());
        assertEquals(2400L, vo.getEstimatedAmountCent());
        assertEquals(60, vo.getMatchedDurationMinutes());
    }

    @Test
    @DisplayName("命中上界 NULL：qty=999 落 [50,∞] → 命中（无上界）")
    void estimate_hitNullUpper() {
        when(baseMapper.selectList(any())).thenReturn(List.of(rule(1L, 50, null, 300L, 120)));
        GzRecycleEstimateVO vo = service.estimate("card", 999);
        assertEquals(999 * 300L, vo.getEstimatedAmountCent());
        assertEquals(120, vo.getMatchedDurationMinutes());
    }

    @Test
    @DisplayName("命中 0：qty=100 但仅配 [1,5][6,10] → 抛「无报价规则」")
    void estimate_noHit() {
        when(baseMapper.selectList(any())).thenReturn(List.of(
            rule(1L, 1, 5, 500L, 30),
            rule(2L, 6, 10, 400L, 60)));
        ServiceException ex = assertThrows(ServiceException.class, () -> service.estimate("card", 100));
        assertTrue(ex.getMessage().contains("无报价规则"));
    }

    @Test
    @DisplayName("命中多：脏数据 [1,5][3,8] 同时命中 qty=4 → 抛「配置有误」")
    void estimate_multipleHit() {
        when(baseMapper.selectList(any())).thenReturn(List.of(
            rule(1L, 1, 5, 500L, 30),
            rule(2L, 3, 8, 400L, 60)));
        ServiceException ex = assertThrows(ServiceException.class, () -> service.estimate("card", 4));
        assertTrue(ex.getMessage().contains("配置有误"));
    }

    @Test
    @DisplayName("入参非法：category 空 / qty<1 → 拒绝")
    void estimate_rejectInvalidArgs() {
        assertThrows(ServiceException.class, () -> service.estimate("", 3));
        assertThrows(ServiceException.class, () -> service.estimate("card", 0));
    }
}
