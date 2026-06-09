package org.dromara.gz.gacha.service.internal;

import org.dromara.gz.gacha.domain.entity.GzGachaPrize;
import org.dromara.gz.gacha.service.internal.ProbabilityNormalizer.Bucket;
import org.dromara.gz.gacha.service.internal.ProbabilityNormalizer.NormalizeResult;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.List;
import java.util.function.LongUnaryOperator;

/**
 * 权重区间随机抽取（GZ-GACHA-104，doc/10 §8.N6 步骤 4）。
 *
 * <p><b>SecureRandom 而非 Random</b>（决策 D4 / 合规）：不可预测，防种子推测出货序列。生成
 * {@code [0, totalWeight)} 的 long，落归一化累积区间定 1 件 —— <b>必出 1 件</b>（doc/10 §8 业务规则）。</p>
 *
 * <p><b>可测试</b>（AC8）：随机源抽象为 {@link LongUnaryOperator}（入参 bound，出 [0,bound) 随机 long）。
 * 生产用 {@link SecureRandom}；单测注入确定性桩（mock 指定 roll 值）验证区间命中正确，不依赖真随机。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-GACHA-104)
 */
@Component
public class SecureRandomDrawer {

    private final SecureRandom secureRandom = new SecureRandom();

    /** 生产随机源：SecureRandom.nextLong(bound)，返回 [0, bound)。 */
    private final LongUnaryOperator randomSource = bound -> secureRandom.nextLong(bound);

    /**
     * 从归一化结果中按权重随机抽 1 件（生产路径）。
     *
     * @param normalized 归一化结果（{@link ProbabilityNormalizer#normalize}）
     * @return 命中的奖品（必非 null）
     */
    public GzGachaPrize draw(NormalizeResult normalized) {
        return draw(normalized, randomSource);
    }

    /**
     * 从归一化结果中按权重随机抽 1 件（可注入随机源，单测用）。
     *
     * <p>roll = randomSource.applyAsLong(totalWeight) ∈ [0, totalWeight)；落第一个
     * {@code roll < cumulativeUpper} 的桶（累积上界单调递增，二分可优化，候选量小线性即可）。</p>
     *
     * @param normalized   归一化结果（totalWeight &gt; 0）
     * @param randomSource 随机源（入 bound 出 [0,bound)）；单测注入确定性桩
     * @return 命中的奖品（必非 null）
     */
    public GzGachaPrize draw(NormalizeResult normalized, LongUnaryOperator randomSource) {
        long total = normalized.totalWeight();
        if (total <= 0) {
            throw new IllegalStateException("总权重 ≤ 0，归一化保证不应发生（等权兜底）");
        }
        long roll = randomSource.applyAsLong(total);
        List<Bucket> buckets = normalized.buckets();
        for (Bucket bucket : buckets) {
            if (roll < bucket.cumulativeUpper()) {
                return bucket.prize();
            }
        }
        // 浮点 / 边界兜底：理论不可达（roll < total = 最后一桶上界），保险返回最后一件
        return buckets.get(buckets.size() - 1).prize();
    }
}
