package org.dromara.gz.common.recycle.job;

import com.aizuda.snailjob.client.job.core.annotation.JobExecutor;
import com.aizuda.snailjob.client.job.core.dto.JobArgs;
import com.aizuda.snailjob.common.log.SnailJobLog;
import com.aizuda.snailjob.model.dto.ExecuteResult;
import lombok.RequiredArgsConstructor;
import org.dromara.gz.common.recycle.service.IGzRecycleBinService;
import org.springframework.stereotype.Component;

/**
 * 回收站物理清理 cron 执行器（GZ-ADMIN-108，AC6）。
 *
 * <p>SnailJob 注解式薄壳，<b>不</b> Flyway INSERT sys_job（仓库无该表，INSERT 启动 hard-fail）。
 * 核心全在 {@link IGzRecycleBinService#cleanupExpired()}（可单测脱离 SnailJob）。</p>
 *
 * <p>控制台注册：任务名 回收站物理清理 / 路由 gzRecycleCleanupJob / CRON {@code 0 0 3 * * ?}（每日 03:00）
 * / 阻塞 DISCARD / 组 ruoyi_group。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-ADMIN-108)
 */
@Component
@RequiredArgsConstructor
@JobExecutor(name = "gzRecycleCleanupJob")
public class GzRecycleCleanupJob {

    private final IGzRecycleBinService recycleBinService;

    public ExecuteResult jobExecute(JobArgs jobArgs) {
        try {
            int deleted = recycleBinService.cleanupExpired();
            String msg = String.format("回收站物理清理完成：删除 %d 行（archived 且超 30 天）", deleted);
            SnailJobLog.REMOTE.info("[GZ-RECYCLE-CLEANUP] {}", msg);
            return ExecuteResult.success(msg);
        } catch (Exception ex) {
            SnailJobLog.REMOTE.error("[GZ-RECYCLE-CLEANUP] 清理异常", ex);
            return ExecuteResult.failure("回收站物理清理异常：" + ex.getMessage());
        }
    }
}
