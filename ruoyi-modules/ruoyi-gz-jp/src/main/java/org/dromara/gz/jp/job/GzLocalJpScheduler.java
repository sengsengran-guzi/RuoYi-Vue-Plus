package org.dromara.gz.jp.job;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 拼团域本地调度（路 C —— 不依赖 SnailJob 服务端，单机部署用 Spring {@code @Scheduled} 直接跑；
 * 照 {@code GzLocalBeanScheduler} 范式）。
 *
 * <p><b>开关</b>：沿用 {@code gz.local-scheduler.enabled}（prod 默认 true，dev/test 缺省即 false），不另设开关。
 * {@code @EnableScheduling} 由 gz-common 的 {@code GzLocalSchedulerConfig} 按同一开关提供。</p>
 *
 * <p><b>只是触发壳</b>：逻辑全在 {@link GzJpUnpaidExpireJob}（单测直接测它）；每轮吞异常只记日志，绝不让调度线程挂。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-JP-110)
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "gz.local-scheduler", name = "enabled", havingValue = "true")
public class GzLocalJpScheduler {

    private final GzJpUnpaidExpireJob unpaidExpireJob;

    /**
     * 未付款订单超时关单（FLOW:F-JP-10.step1）：拼团单与代切单同一任务。
     * 默认每 5 分钟（可 {@code gz.local-scheduler.jp-unpaid-expire-ms} 调）。
     */
    @Scheduled(fixedDelayString = "${gz.local-scheduler.jp-unpaid-expire-ms:300000}",
        initialDelayString = "${gz.local-scheduler.initial-delay-ms:60000}")
    public void unpaidExpire() {
        try {
            // 统计日志由 GzJpUnpaidExpireJob 在有单时自己打（无单不打，避免每 5 分钟一行空日志）
            unpaidExpireJob.run();
        } catch (Exception ex) {
            log.error("[gz-local-sched] jp-unpaid-expire FAILED", ex);
        }
    }
}
