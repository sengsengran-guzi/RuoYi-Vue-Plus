package org.dromara.gz.recycle.job;

import com.aizuda.snailjob.client.job.core.annotation.JobExecutor;
import com.aizuda.snailjob.client.job.core.dto.JobArgs;
import com.aizuda.snailjob.common.log.SnailJobLog;
import com.aizuda.snailjob.model.dto.ExecuteResult;
import lombok.RequiredArgsConstructor;
import org.dromara.gz.recycle.service.IGzRecycleAppointmentService;
import org.springframework.stereotype.Component;

/**
 * 回收预约 paying→paid 回写 cron 执行器（GZ-RECYCLE-003 AC2，doc/10 §13.N10）。
 *
 * <p><b>与 PAY-105 查单解耦</b>：PAY-105 {@code gzPayoutQueryTask} 只推进 payout 单本身（processing→success/failed）；
 * 回收预约单 paying→paid 的回写靠本钩子按 {@code out_payout_no} 关联 payout 终态收敛。两者独立 cron，PAY-105
 * 查单先把 payout 单推到 success，本任务下一轮扫到即回写预约 paid（最终一致，到账有延迟可接受）。</p>
 *
 * <p><b>调度框架</b>：SnailJob（com.aizuda），<b>不</b>通过 Flyway INSERT {@code sys_job}（memory
 * `scheduler-is-snailjob`）。本类只是触发壳，核心 {@link IGzRecycleAppointmentService#syncPayoutResult()}
 * 在 service（脱离 SnailJob server 可单测）。</p>
 *
 * <p><b>SnailJob 控制台建议配置</b>：</p>
 * <ul>
 *   <li>任务名称：GZ-RECYCLE-PAYOUT-SYNC</li>
 *   <li>执行器路由（executor_info）：{@code gzRecyclePayoutSyncTask}（= 本类 @JobExecutor name）</li>
 *   <li>触发类型：CRON，表达式 {@code 0 1/2 * * * ?}（每 2 分钟，错峰 PAY-105 查单 1 分钟，容器时区 Asia/Shanghai）</li>
 *   <li>阻塞策略：丢弃（DISCARD）；组：{@code ruoyi_group}</li>
 * </ul>
 *
 * @author kevin-coder (sensenran-guzi · GZ-RECYCLE-003)
 */
@Component
@RequiredArgsConstructor
@JobExecutor(name = "gzRecyclePayoutSyncTask")
public class GzRecyclePayoutSyncJob {

    private final IGzRecycleAppointmentService recycleService;

    /**
     * SnailJob 触发入口。委托 service 扫 paying 单 → 查对应 payout 终态 → 回写 paid / payout_failed。
     */
    public ExecuteResult jobExecute(JobArgs jobArgs) {
        try {
            int paid = recycleService.syncPayoutResult();
            String msg = "回收 paying→paid 回写完成：本轮回写 paid " + paid + " 单";
            SnailJobLog.REMOTE.info("[GZ-RECYCLE-PAYOUT-SYNC] {}", msg);
            return ExecuteResult.success(msg);
        } catch (Exception ex) {
            SnailJobLog.REMOTE.error("[GZ-RECYCLE-PAYOUT-SYNC] 任务执行异常", ex);
            return ExecuteResult.failure("回收 paid 回写任务异常：" + ex.getMessage());
        }
    }
}
