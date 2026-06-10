package org.dromara.gz.recon.job;

import com.aizuda.snailjob.client.job.core.annotation.JobExecutor;
import com.aizuda.snailjob.client.job.core.dto.JobArgs;
import com.aizuda.snailjob.common.log.SnailJobLog;
import com.aizuda.snailjob.model.dto.ExecuteResult;
import lombok.RequiredArgsConstructor;
import org.dromara.gz.recon.service.IGzReconBatchService;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * 对账每日跑批 cron 执行器（GZ-ADMIN-105，doc/10 §10.N1）。
 *
 * <p>SnailJob（com.aizuda）注解式执行器薄壳，<b>不</b>通过 Flyway INSERT sys_job 注册（仓库无该表，
 * INSERT 会启动 hard-fail）。任务名/CRON/路由在 SnailJob 控制台配置，路由名对齐 @JobExecutor(name)。
 * 核心逻辑全在 {@link IGzReconBatchService#runDaily}（单测脱离 SnailJob）。</p>
 *
 * <p>控制台注册：任务名 GZ-RECON-DAILY / 路由 gzReconDailyJob / CRON {@code 0 0 2 * * ?}（每日 02:00 跑前一日）
 * / 阻塞 DISCARD / 组 ruoyi_group。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-ADMIN-105)
 */
@Component
@RequiredArgsConstructor
@JobExecutor(name = "gzReconDailyJob")
public class GzReconDailyJobExecutor {

    private final IGzReconBatchService batchService;

    public ExecuteResult jobExecute(JobArgs jobArgs) {
        try {
            LocalDate yesterday = LocalDate.now().minusDays(1);
            int count = batchService.runDaily(yesterday);
            String msg = String.format("对账每日跑批完成：business_day=%s，业务线 %d 条", yesterday, count);
            SnailJobLog.REMOTE.info("[GZ-RECON-DAILY] {}", msg);
            return ExecuteResult.success(msg);
        } catch (Exception ex) {
            SnailJobLog.REMOTE.error("[GZ-RECON-DAILY] 跑批异常", ex);
            return ExecuteResult.failure("对账每日跑批异常：" + ex.getMessage());
        }
    }
}
