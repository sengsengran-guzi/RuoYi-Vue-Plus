package org.dromara.gz.bean.job;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.gz.bean.service.IGzBeanBookingService;
import org.dromara.gz.bean.service.IGzBeanBookingService.ExpiredUnpaidResult;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 拼豆域本地补偿定时任务（路 C —— 不依赖 SnailJob 服务端，单机部署用 Spring {@code @Scheduled} 直接跑）。
 *
 * <p><b>背景</b>：生产/预发未部署 SnailJob，{@link GzBeanUnpaidExpireJob}（{@code @JobExecutor} 壳）从不触发 →
 * 用户点「提交」后放弃支付的 {@code pending/paying} 占位单永不回收，逐格配额被幽灵占位吃满 → mp「已约满」。
 * 本类用 Spring 调度直接跑同一个 {@link IGzBeanBookingService#markExpiredUnpaidBatch(int)}（15min 超时回收，
 * 幂等 + 单条异常隔离 + 释放配额）。no_show 释放座位有 admin「看板批量结单」手动兜底，不在此列。</p>
 *
 * <p><b>开关</b>：{@code gz.local-scheduler.enabled}（prod 默认 true，dev/test 缺省即 false）。日后若部署
 * SnailJob（路 A）→ {@code GZ_LOCAL_SCHEDULER_ENABLED=false} 关掉本地版避免双跑。</p>
 *
 * @author kevin-coder (sensenran-guzi · 本地补偿调度)
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "gz.local-scheduler", name = "enabled", havingValue = "true")
public class GzLocalBeanScheduler {

    /** 未付占位单超时回收分钟数（与 {@link GzBeanUnpaidExpireJob} 默认一致）。 */
    private static final int UNPAID_TIMEOUT_MINUTES = 15;

    private final IGzBeanBookingService bookingService;

    /**
     * 拼豆未付占位单超时回收（对齐 {@link GzBeanUnpaidExpireJob}）：扫 {@code pending + unpaid/paying + create_time
     * 超 15min} 的占位单 → 关单释放逐格配额。默认每 5min（可 {@code gz.local-scheduler.unpaid-expire-ms} 调）。
     */
    @Scheduled(fixedDelayString = "${gz.local-scheduler.unpaid-expire-ms:300000}",
        initialDelayString = "${gz.local-scheduler.initial-delay-ms:60000}")
    public void unpaidExpire() {
        try {
            ExpiredUnpaidResult r = bookingService.markExpiredUnpaidBatch(UNPAID_TIMEOUT_MINUTES);
            if (r.scanned() > 0) {
                log.info("[gz-local-sched] bean-unpaid-expire scanned={} closed={} skipped={} failed={}",
                    r.scanned(), r.closed(), r.skipped(), r.failed());
            }
        } catch (Exception ex) {
            log.error("[gz-local-sched] bean-unpaid-expire FAILED", ex);
        }
    }
}
