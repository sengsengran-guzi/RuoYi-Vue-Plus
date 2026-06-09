package org.dromara.gz.gacha.service.internal;

import org.dromara.gz.gacha.domain.entity.GzGachaPrize;
import org.dromara.gz.gacha.service.internal.ProbabilityNormalizer.NormalizeResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ProbabilityNormalizer + SecureRandomDrawer 单测（GZ-GACHA-104 AC8 场景 6 — 概率随机性 + 区间正确性）。
 *
 * <p>覆盖：① 归一化累积区间正确（权重比 → 累积上界）；② 等权兜底（全 weight≤0 → 等权，必出）；
 * ③ drawer 确定性区间命中（注入 roll 桩）；④ <b>χ² 检验</b>：10000 次抽样各商品频次贴近 weight 比，
 * χ² 统计量 &lt; 临界值（P &gt; 0.05）。全无 DB / Spring。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-GACHA-104)
 */
@Tag("dev")
@DisplayName("ProbabilityNormalizer + SecureRandomDrawer 单测 — 归一化区间 + χ² 随机性")
class ProbabilityNormalizerTest {

    private final ProbabilityNormalizer normalizer = new ProbabilityNormalizer();
    private final SecureRandomDrawer drawer = new SecureRandomDrawer();

    @Test
    @DisplayName("归一化累积区间：weight 70/20/10 → 累积上界 70/90/100，总权重 100")
    void normalize_cumulativeBounds() {
        List<GzGachaPrize> candidates = List.of(
            prize(1L, 70), prize(2L, 20), prize(3L, 10));
        NormalizeResult r = normalizer.normalize(candidates);
        assertEquals(100L, r.totalWeight());
        assertEquals(70L, r.buckets().get(0).cumulativeUpper());
        assertEquals(90L, r.buckets().get(1).cumulativeUpper());
        assertEquals(100L, r.buckets().get(2).cumulativeUpper());
    }

    @Test
    @DisplayName("等权兜底：全 weight=0 → 每件计权重 1，总权重=候选数（必出 1 件）")
    void normalize_allZeroWeight_fallbackEqual() {
        List<GzGachaPrize> candidates = List.of(prize(1L, 0), prize(2L, 0), prize(3L, 0));
        NormalizeResult r = normalizer.normalize(candidates);
        assertEquals(3L, r.totalWeight());
        assertEquals(1L, r.buckets().get(0).cumulativeUpper());
        assertEquals(3L, r.buckets().get(2).cumulativeUpper());
    }

    @Test
    @DisplayName("候选为空 → 抛 IllegalArgumentException（开盒事务应已保证非空）")
    void normalize_emptyCandidates_throws() {
        assertThrows(IllegalArgumentException.class, () -> normalizer.normalize(List.of()));
    }

    @Test
    @DisplayName("drawer 确定性区间命中：roll=0 → 第 1 件；roll=69 → 第 1 件；roll=70 → 第 2 件；roll=99 → 第 3 件")
    void drawer_deterministicBuckets() {
        List<GzGachaPrize> candidates = List.of(prize(1L, 70), prize(2L, 20), prize(3L, 10));
        NormalizeResult r = normalizer.normalize(candidates);
        assertSame(candidates.get(0), drawer.draw(r, bound -> 0L));
        assertSame(candidates.get(0), drawer.draw(r, bound -> 69L));
        assertSame(candidates.get(1), drawer.draw(r, bound -> 70L));
        assertSame(candidates.get(1), drawer.draw(r, bound -> 89L));
        assertSame(candidates.get(2), drawer.draw(r, bound -> 90L));
        assertSame(candidates.get(2), drawer.draw(r, bound -> 99L));
    }

    @Test
    @DisplayName("χ² 检验：10000 次 SecureRandom 抽样，频次贴近 weight 比 70/20/10，χ² < 临界 5.991（df=2, P>0.05）")
    void drawer_chiSquareGoodnessOfFit() {
        int[] weights = {70, 20, 10};
        List<GzGachaPrize> candidates = List.of(prize(1L, weights[0]), prize(2L, weights[1]), prize(3L, weights[2]));
        NormalizeResult r = normalizer.normalize(candidates);

        int n = 10000;
        Map<Long, LongAdder> counts = new ConcurrentHashMap<>();
        candidates.forEach(p -> counts.put(p.getId(), new LongAdder()));
        for (int i = 0; i < n; i++) {
            GzGachaPrize won = drawer.draw(r); // 真 SecureRandom
            counts.get(won.getId()).increment();
        }

        // χ² = Σ (observed - expected)² / expected
        int totalWeight = weights[0] + weights[1] + weights[2];
        double chiSquare = 0.0;
        for (int i = 0; i < candidates.size(); i++) {
            long observed = counts.get(candidates.get(i).getId()).sum();
            double expected = (double) n * weights[i] / totalWeight;
            chiSquare += Math.pow(observed - expected, 2) / expected;
        }
        // df=2 临界值（α=0.05）= 5.991；χ² < 5.991 → 不拒绝「贴合 weight 比」原假设（P > 0.05）
        System.out.printf("[χ²] n=%d counts=%s chiSquare=%.4f (临界 5.991, df=2, P>0.05)%n",
            n, counts, chiSquare);
        assertTrue(chiSquare < 5.991,
            "χ² = " + chiSquare + " 应 < 5.991（P>0.05）；偶发越界重跑（真随机有小概率）");
        // 每件都被抽到（必出，无空奖）
        candidates.forEach(p -> assertTrue(counts.get(p.getId()).sum() > 0, "每件都应被抽到"));
    }

    private GzGachaPrize prize(Long id, int weight) {
        GzGachaPrize p = new GzGachaPrize();
        p.setId(id);
        p.setWeight(weight);
        p.setStockRemain(999);
        p.setVersion(0);
        p.setEnabled(1);
        return p;
    }
}
