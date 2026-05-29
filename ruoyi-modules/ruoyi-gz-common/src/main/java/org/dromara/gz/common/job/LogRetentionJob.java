package org.dromara.gz.common.job;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.gz.common.service.IGzOperLogService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * GZ-SYS-006 操作日志保留策略调度任务。
 *
 * <p>合同 §2 + doc/02 §2.1 第 252 行：操作日志保留 12 个月，过期自动清理。</p>
 *
 * <p>调度：每月 1 号凌晨 3:00 — 错峰避免业务高峰；使用 Spring {@code @Scheduled}
 * （{@code ruoyi-common-job} 已 {@code @EnableScheduling}）。</p>
 *
 * <p>当前为单实例调度（V1.0 部署只有 1 个 ruoyi-admin 实例）；多实例时需迁到 snail-job 防止重跑。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-SYS-006)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LogRetentionJob {

    /** 保留月数（doc/02 §2.1 第 252 行）。 */
    private static final int RETENTION_MONTHS = 12;

    private final IGzOperLogService operLogService;

    /**
     * 每月 1 号凌晨 3:00 清理 12 个月前的 sys_oper_log 记录。
     *
     * <p>cron: 秒 分 时 日 月 周 — "0 0 3 1 * ?"</p>
     */
    @Scheduled(cron = "0 0 3 1 * ?")
    public void cleanOldLogs() {
        long start = System.currentTimeMillis();
        log.info("[GZ-SYS-006] LogRetentionJob 启动：清理 {} 个月前的操作日志", RETENTION_MONTHS);
        try {
            int deleted = operLogService.cleanOlderThan(RETENTION_MONTHS);
            long cost = System.currentTimeMillis() - start;
            log.info("[GZ-SYS-006] LogRetentionJob 完成：deleted={} costMs={}", deleted, cost);
        } catch (Exception e) {
            // 不向上抛 — 月度任务挂了不能影响业务进程；Kevin 通过日志 + 监控发现
            log.error("[GZ-SYS-006] LogRetentionJob 执行失败", e);
        }
    }
}
