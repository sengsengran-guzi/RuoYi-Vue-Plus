package org.dromara.gz.jp.job;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.tenant.helper.TenantHelper;
import org.dromara.gz.jp.domain.entity.GzJpRefund;
import org.dromara.gz.jp.mapper.GzJpRefundMapper;
import org.dromara.gz.jp.service.IGzJpRefundService;
import org.dromara.gz.jp.service.IGzJpRefundService.CompensateOutcome;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 拼团退款<b>卡单自动补偿</b>（GZ-JP-111，FLOW:F-JP-10.step2，REQ-FULFILL-008）。
 *
 * <p><b>不是 SnailJob 的 {@code @JobExecutor}</b>（prod 没部署 SnailJob）—— 本类是核心逻辑，由
 * {@link GzLocalJpScheduler}（Spring {@code @Scheduled}，开关 {@code gz.local-scheduler.enabled}）每 5 分钟调一次。</p>
 *
 * <p>扫 {@code gz_jp_refund.status='refunding'} 且 {@code update_time} 早于静默期
 * （{@link IGzJpRefundService#REFUNDING_SILENCE}，10 分钟，与 admin「重新发起」同一口径）的退款单，
 * <b>逐单</b>交给 {@link IGzJpRefundService#compensateStaleRefund}：查微信退款单 → SUCCESS 走回调同一落账 /
 * ABNORMAL·CLOSED 落 refund_failed / NOT_FOUND 同号补发 / PROCESSING 不动。两种 {@code refund_type} 都扫。</p>
 *
 * <p><b>每单独立</b>：单单 try/catch，一单抛错（含查单失败）只记 ERROR 计 failed，继续下一单；
 * 该单仍是 {@code refunding}，下一轮自然再扫到。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-JP-111)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GzJpRefundCompensateJob {

    /** 每轮最多处理几张（处理不完的下一轮接着扫） */
    static final int BATCH_LIMIT = 200;

    private final GzJpRefundMapper refundMapper;
    private final IGzJpRefundService refundService;

    /**
     * 跑一轮。调度线程没有登录上下文 → {@code TenantHelper.ignore} 全租户扫。
     *
     * @return 本轮统计
     */
    public CompensateResult run() {
        return TenantHelper.ignore(() -> {
            LocalDateTime deadline = LocalDateTime.now().minus(IGzJpRefundService.REFUNDING_SILENCE);
            List<GzJpRefund> stale = refundMapper.selectStaleRefunding(deadline, BATCH_LIMIT);
            Map<CompensateOutcome, Integer> counts = new EnumMap<>(CompensateOutcome.class);
            if (stale == null || stale.isEmpty()) {
                return new CompensateResult(0, counts, 0);
            }
            if (stale.size() >= BATCH_LIMIT) {
                log.warn("[gz-jp-refund-compensate] 本轮卡单达到上限 {} 张，余下的下一轮处理", BATCH_LIMIT);
            }
            int failed = 0;
            for (GzJpRefund refund : stale) {
                try {
                    CompensateOutcome outcome = refundService.compensateStaleRefund(refund);
                    counts.merge(outcome, 1, Integer::sum);
                } catch (Exception ex) {
                    failed++;
                    log.error("[gz-jp-refund-compensate] 补偿异常 refund_no={} id={}，本单跳过、下轮再试",
                        refund.getRefundNo(), refund.getId(), ex);
                }
            }
            log.info("[gz-jp-refund-compensate] 静默期={}min scanned={} outcomes={} failed={}",
                IGzJpRefundService.REFUNDING_SILENCE.toMinutes(), stale.size(), counts, failed);
            return new CompensateResult(stale.size(), counts, failed);
        });
    }

    /**
     * 一轮的统计。
     *
     * @param scanned  扫到的卡单数
     * @param outcomes 各补偿结果的张数（没出现的结果不在 map 里）
     * @param failed   抛异常（含查单失败）的张数
     */
    public record CompensateResult(int scanned, Map<CompensateOutcome, Integer> outcomes, int failed) {

        /**
         * 取某个结果的张数。
         *
         * @param outcome 补偿结果
         * @return 张数（没出现为 0）
         */
        public int count(CompensateOutcome outcome) {
            return outcomes.getOrDefault(outcome, 0);
        }
    }
}
