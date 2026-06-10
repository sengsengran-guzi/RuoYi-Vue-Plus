package org.dromara.gz.recon.job;

import com.aizuda.snailjob.client.job.core.annotation.JobExecutor;
import com.aizuda.snailjob.client.job.core.dto.JobArgs;
import com.aizuda.snailjob.common.log.SnailJobLog;
import com.aizuda.snailjob.model.dto.ExecuteResult;
import lombok.RequiredArgsConstructor;
import org.dromara.gz.recon.service.IGzReconBatchService;
import org.springframework.stereotype.Component;

import java.time.YearMonth;

/**
 * 对账月度跑批 cron 执行器（GZ-ADMIN-105，doc/10 §10.N2）。
 *
 * <p>SnailJob 薄壳，转调 {@link IGzReconBatchService#runMonthly}（聚合上月 daily → A/B 月对账单 + 分成）。
 * 约束同 {@link GzReconDailyJobExecutor}（禁 sys_job INSERT）。</p>
 *
 * <p>控制台注册：任务名 GZ-RECON-MONTHLY / 路由 gzReconMonthlyJob / CRON {@code 0 0 3 1 * ?}（每月 1 日 03:00 跑前月）
 * / 阻塞 DISCARD / 组 ruoyi_group。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-ADMIN-105)
 */
@Component
@RequiredArgsConstructor
@JobExecutor(name = "gzReconMonthlyJob")
public class GzReconMonthlyJobExecutor {

    private final IGzReconBatchService batchService;

    public ExecuteResult jobExecute(JobArgs jobArgs) {
        try {
            YearMonth lastMonth = YearMonth.now().minusMonths(1);
            int count = batchService.runMonthly(lastMonth);
            String msg = String.format("对账月度跑批完成：business_month=%s，业务线 %d 条", lastMonth, count);
            SnailJobLog.REMOTE.info("[GZ-RECON-MONTHLY] {}", msg);
            return ExecuteResult.success(msg);
        } catch (Exception ex) {
            SnailJobLog.REMOTE.error("[GZ-RECON-MONTHLY] 跑批异常", ex);
            return ExecuteResult.failure("对账月度跑批异常：" + ex.getMessage());
        }
    }
}
