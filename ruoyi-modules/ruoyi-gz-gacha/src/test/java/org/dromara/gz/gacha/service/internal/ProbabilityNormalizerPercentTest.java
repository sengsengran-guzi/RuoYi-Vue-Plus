package org.dromara.gz.gacha.service.internal;

import org.dromara.gz.gacha.domain.entity.GzGachaPrize;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ProbabilityNormalizer.normalizeToPercent 单测（GZ-GACHA-103 AC7 — 概率公示实时归一化百分比）。
 *
 * <p>口径权威 doc/11 §7.2：{@code P = weight_i / Σ weight_j × 100}，入池子集 {@code enabled=1 AND
 * stock_remain>0}，HALF_UP 2 位。与开盒事务（GACHA-104）<b>同一 Bean 同口径</b>（{@code isInPool} 谓词 +
 * {@code normalize} 总权重），单一真源不漂移。</p>
 *
 * <p>覆盖 AC7：① 正常多奖品归一化（和 ≈ 100）；② 仅 1 个有库存（= 100.00）；③ 全部库存=0（全 null）；
 * ④ disabled 不入池；⑤ weight 总和巨大不溢出（BigDecimal）。全无 DB / Spring。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-GACHA-103)
 */
@Tag("dev")
@DisplayName("ProbabilityNormalizer.normalizeToPercent 单测 — 概率公示实时归一化百分比")
class ProbabilityNormalizerPercentTest {

    private final ProbabilityNormalizer normalizer = new ProbabilityNormalizer();

    @Test
    @DisplayName("① 正常多奖品归一化：weight 70/20/10 → 70.00/20.00/10.00，和 = 100.00")
    void normalizeToPercent_normal() {
        List<GzGachaPrize> prizes = List.of(
            prize(1L, 70, 5, 1), prize(2L, 20, 5, 1), prize(3L, 10, 5, 1));
        Map<Long, BigDecimal> r = normalizer.normalizeToPercent(prizes);
        assertEquals(new BigDecimal("70.00"), r.get(1L));
        assertEquals(new BigDecimal("20.00"), r.get(2L));
        assertEquals(new BigDecimal("10.00"), r.get(3L));
        // 和 = 100.00
        BigDecimal sum = r.values().stream().filter(v -> v != null).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(new BigDecimal("100.00"), sum);
    }

    @Test
    @DisplayName("② 仅 1 个奖品有库存：该条 = 100.00（其余售罄 → null，不参与分母）")
    void normalizeToPercent_onlyOneInStock() {
        List<GzGachaPrize> prizes = List.of(
            prize(1L, 70, 0, 1),  // 售罄
            prize(2L, 20, 3, 1),  // 唯一有货
            prize(3L, 10, 0, 1)); // 售罄
        Map<Long, BigDecimal> r = normalizer.normalizeToPercent(prizes);
        assertNull(r.get(1L));
        assertEquals(new BigDecimal("100.00"), r.get(2L));
        assertNull(r.get(3L));
    }

    @Test
    @DisplayName("③ 全部库存=0：全部返回 null（入池为空 → CTA 应置灰）")
    void normalizeToPercent_allEmpty() {
        List<GzGachaPrize> prizes = List.of(
            prize(1L, 70, 0, 1), prize(2L, 20, 0, 1), prize(3L, 10, 0, 1));
        Map<Long, BigDecimal> r = normalizer.normalizeToPercent(prizes);
        assertNull(r.get(1L));
        assertNull(r.get(2L));
        assertNull(r.get(3L));
    }

    @Test
    @DisplayName("④ disabled 奖品不入池：enabled=0 → null，不计入分母（其余按剩余归一化）")
    void normalizeToPercent_disabledExcluded() {
        List<GzGachaPrize> prizes = List.of(
            prize(1L, 50, 5, 1),  // 入池
            prize(2L, 50, 5, 0),  // disabled → 不入池
            prize(3L, 50, 5, 1)); // 入池
        Map<Long, BigDecimal> r = normalizer.normalizeToPercent(prizes);
        // 分母 = 50 + 50 = 100（disabled 的 50 不计）→ 各 50.00
        assertEquals(new BigDecimal("50.00"), r.get(1L));
        assertNull(r.get(2L));
        assertEquals(new BigDecimal("50.00"), r.get(3L));
    }

    @Test
    @DisplayName("⑤ weight 总和巨大不溢出：3 个 Integer.MAX_VALUE → 各 33.33，BigDecimal 无溢出")
    void normalizeToPercent_hugeWeightNoOverflow() {
        int max = Integer.MAX_VALUE;
        List<GzGachaPrize> prizes = List.of(
            prize(1L, max, 5, 1), prize(2L, max, 5, 1), prize(3L, max, 5, 1));
        Map<Long, BigDecimal> r = normalizer.normalizeToPercent(prizes);
        // 三等权 → 各 33.33（HALF_UP 2 位）
        assertEquals(new BigDecimal("33.33"), r.get(1L));
        assertEquals(new BigDecimal("33.33"), r.get(2L));
        assertEquals(new BigDecimal("33.33"), r.get(3L));
        // 和 ≈ 99.99（HALF_UP 末位漂移，公示说明文案标注「实时值不构成保底承诺」，R3 可接受）
        BigDecimal sum = r.values().stream().filter(v -> v != null).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(new BigDecimal("99.99"), sum);
    }

    @Test
    @DisplayName("空列表 / null → 空 map（防御）")
    void normalizeToPercent_emptyOrNull() {
        assertTrue(normalizer.normalizeToPercent(List.of()).isEmpty());
        assertTrue(normalizer.normalizeToPercent(null).isEmpty());
    }

    /**
     * 构造奖品 entity。
     *
     * @param id      主键
     * @param weight  权重
     * @param remain  剩余库存
     * @param enabled 1=参与 / 0=临停
     */
    private GzGachaPrize prize(Long id, int weight, int remain, int enabled) {
        GzGachaPrize p = new GzGachaPrize();
        p.setId(id);
        p.setWeight(weight);
        p.setStockRemain(remain);
        p.setEnabled(enabled);
        p.setVersion(0);
        return p;
    }
}
