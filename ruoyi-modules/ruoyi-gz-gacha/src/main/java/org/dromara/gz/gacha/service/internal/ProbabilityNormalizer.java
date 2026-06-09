package org.dromara.gz.gacha.service.internal;

import org.dromara.gz.gacha.domain.entity.GzGachaPrize;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 概率归一化（GZ-GACHA-104，doc/10 §8.N3 概率公示 + §8.N6 开盒事务一次性归一化）。
 *
 * <p><b>口径单一真源</b>（风险 R3）：mp 概率公示（GACHA-103）与开盒事务（GACHA-104）<b>复用本 Bean
 * 同一口径</b>，避免「公示概率」与「实际出货概率」漂移。归一化公式（doc/11 §7.2 计算口径）：</p>
 * <pre>
 *   P(prize_i) = weight_i / Σ weight_j ，  j ∈ {enabled=1 AND stock_remain>0 的在池奖品}
 * </pre>
 *
 * <p><b>开盒事务一致性</b>（doc/10 §8.E3）：事务内已 {@code SELECT FOR UPDATE} 锁住候选快照，本归一化
 * <b>一次性</b>对当前候选集计算，事务内不重读旧权重；并发改概率配置不影响进行中的事务（用旧权重出，
 * 一致性优先）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-GACHA-104)
 */
@Component
public class ProbabilityNormalizer {

    /**
     * 对候选奖品按 weight 归一化，产出累积权重区间（开盒抽取用）。
     *
     * <p>weight ≤ 0 的候选视为权重 0（不参与出货，但保留在列表 — 用于公示场景一致显示 0%）；
     * 全部候选总权重为 0（极端配置：所有在池奖品 weight=0）→ 退化为<b>等权</b>（每个候选权重计 1），
     * 保证「必出 1 件」（doc/10 §8 业务规则）不因配置失误而抛错。</p>
     *
     * @param candidates 在池有货候选（enabled=1 AND stock_remain>0，已 FOR UPDATE 锁）
     * @return 归一化结果（含每候选累积上界 + 总权重，供 {@link SecureRandomDrawer} 落区间）
     */
    public NormalizeResult normalize(List<GzGachaPrize> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            throw new IllegalArgumentException("候选奖品为空，无法归一化（开盒事务应已保证候选非空）");
        }
        // 是否所有候选 weight ≤ 0（极端配置）→ 退化等权
        boolean allNonPositive = candidates.stream()
            .allMatch(p -> p.getWeight() == null || p.getWeight() <= 0);

        List<Bucket> buckets = new ArrayList<>(candidates.size());
        long cumulative = 0;
        for (GzGachaPrize prize : candidates) {
            long w;
            if (allNonPositive) {
                w = 1; // 等权兜底（必出 1 件）
            } else {
                int raw = prize.getWeight() == null ? 0 : prize.getWeight();
                w = Math.max(raw, 0);
            }
            cumulative += w;
            buckets.add(new Bucket(prize, cumulative));
        }
        return new NormalizeResult(buckets, cumulative);
    }

    /**
     * 入池判定（GZ-GACHA-103 概率公示 / GACHA-104 开盒事务共用同一谓词，单一真源）。
     *
     * <p>口径锚定 doc/11 §7.2：{@code j ∈ {enabled=1 AND stock_remain>0}}。开盒事务的
     * {@code GzGachaPrizeMapper#selectInPoolForUpdate} 用<b>同一条 SQL 谓词</b>
     * （{@code enabled = 1 AND stock_remain > 0}）锁候选；本方法是其 Java 侧等价判定 ——
     * 概率公示与实际出货池由<b>同一谓词</b>界定，避免「公示概率」与「实际出货概率」漂移。</p>
     *
     * @param prize 奖品（enabled / stockRemain 可空，空视为不入池）
     * @return true=在池（参与归一化分母）/ false=不在池（disabled 或售罄，公示返回 null）
     */
    public static boolean isInPool(GzGachaPrize prize) {
        return prize != null
            && prize.getEnabled() != null && prize.getEnabled() == 1
            && prize.getStockRemain() != null && prize.getStockRemain() > 0;
    }

    /**
     * 概率公示百分比（GZ-GACHA-103 AC2，doc/10 §8.N3 实时归一化）。
     *
     * <p><b>与开盒事务同口径</b>（强约束 #2/#3）：入池子集由 {@link #isInPool} 界定（同 GACHA-104 的
     * {@code selectInPoolForUpdate} 谓词），其总权重复用 {@link #normalize}（同 weight≤0 视 0、全 0 等权
     * 兜底逻辑）。<b>不另写一份累加</b> —— 单一真源。</p>
     *
     * <p>公式（doc/11 §7.2）：{@code P(prize_i) = weight_i / Σ weight_j × 100}，
     * {@code BigDecimal} HALF_UP 保留 2 位小数（如 {@code 12.34}）。</p>
     *
     * <ul>
     *   <li><b>不在池</b>（{@code enabled=0} 或 {@code stock_remain=0}）→ map 中 value 为 {@code null}
     *       （前端展示 "—"，不参与分母）。</li>
     *   <li><b>入池子集为空</b>（全售罄 / 全 disabled）→ 所有 value 均为 {@code null}（CTA 应置灰，
     *       机器多为 {@code auto_off} 态）。</li>
     *   <li><b>weight 总和巨大</b>不溢出：BigDecimal 计算（决策 D3 / R3）。</li>
     * </ul>
     *
     * @param allPrizes 该机器全部奖品（含售罄 / disabled，不预过滤 —— 决策 D2 前端灰显）
     * @return 保留插入顺序的 {@code prizeId → normalizedProbability(%)}（不在池为 null）
     */
    public Map<Long, BigDecimal> normalizeToPercent(List<GzGachaPrize> allPrizes) {
        Map<Long, BigDecimal> result = new LinkedHashMap<>();
        if (allPrizes == null || allPrizes.isEmpty()) {
            return result;
        }
        // 入池子集（与开盒事务 selectInPoolForUpdate 同谓词）
        List<GzGachaPrize> inPool = allPrizes.stream().filter(ProbabilityNormalizer::isInPool).toList();
        if (inPool.isEmpty()) {
            // 入池为空：全部返回 null（边界，AC2 — CTA 置灰）
            for (GzGachaPrize p : allPrizes) {
                result.put(p.getId(), null);
            }
            return result;
        }
        // 复用 normalize 算总权重（同等权兜底口径，单一真源）
        long totalWeight = normalize(inPool).totalWeight();
        BigDecimal total = BigDecimal.valueOf(totalWeight);
        // 入池每条算 weight_i / total × 100（HALF_UP 2 位）；不在池为 null
        boolean allNonPositive = inPool.stream()
            .allMatch(p -> p.getWeight() == null || p.getWeight() <= 0);
        for (GzGachaPrize p : allPrizes) {
            if (!isInPool(p)) {
                result.put(p.getId(), null);
                continue;
            }
            // 与 normalize 一致：全 0 等权时每条计权重 1，否则取 max(weight,0)
            long w = allNonPositive ? 1L : Math.max(p.getWeight() == null ? 0 : p.getWeight(), 0);
            BigDecimal percent = BigDecimal.valueOf(w)
                .multiply(BigDecimal.valueOf(100))
                .divide(total, 2, RoundingMode.HALF_UP);
            result.put(p.getId(), percent);
        }
        return result;
    }

    /**
     * 归一化结果：累积区间桶列表 + 总权重。
     *
     * @param buckets     每候选的累积上界（上界 exclusive 由 drawer 判定，单调递增）
     * @param totalWeight 总权重（&gt; 0，因等权兜底）
     */
    public record NormalizeResult(List<Bucket> buckets, long totalWeight) {
    }

    /**
     * 单个候选的累积权重桶。
     *
     * @param prize             候选奖品
     * @param cumulativeUpper   累积权重上界（[prev, cumulativeUpper) 命中本候选）
     */
    public record Bucket(GzGachaPrize prize, long cumulativeUpper) {
    }
}
