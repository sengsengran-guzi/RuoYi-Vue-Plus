package org.dromara.gz.recycle.job;

import com.aizuda.snailjob.client.job.core.annotation.JobExecutor;
import com.aizuda.snailjob.client.job.core.dto.JobArgs;
import com.aizuda.snailjob.common.log.SnailJobLog;
import com.aizuda.snailjob.model.dto.ExecuteResult;
import lombok.RequiredArgsConstructor;
import org.dromara.gz.recycle.service.IGzRecycleAppointmentService;
import org.springframework.stereotype.Component;

/**
 * 回收预约 no_show 凌晨兜底 cron 执行器（GZ-RECYCLE-003 AC7，doc/10 §13.N12 / F12.3）。
 *
 * <p><b>店员标记优先</b>（到店核对环节天然覆盖）；本任务兜底扫超 {@code appt_date} 仍 {@code submitted}
 * 的过期单 → {@code no_show}（未到店未取消）。仅 submitted 单受影响（已 confirmed_onsite/paying/paid 不误标）。</p>
 *
 * <p><b>调度框架</b>：SnailJob（com.aizuda），<b>不</b>通过 Flyway INSERT {@code sys_job}（memory
 * `scheduler-is-snailjob`）。核心 {@link IGzRecycleAppointmentService#markExpiredNoShow()} 在 service（可单测）。</p>
 *
 * <p><b>SnailJob 控制台建议配置</b>：</p>
 * <ul>
 *   <li>任务名称：GZ-RECYCLE-NO-SHOW</li>
 *   <li>执行器路由（executor_info）：{@code gzRecycleNoShowTask}（= 本类 @JobExecutor name）</li>
 *   <li>触发类型：CRON，表达式 {@code 0 30 3 * * ?}（每日凌晨 03:30，容器时区 Asia/Shanghai）</li>
 *   <li>阻塞策略：丢弃（DISCARD）；组：{@code ruoyi_group}</li>
 * </ul>
 *
 * @author kevin-coder (sensenran-guzi · GZ-RECYCLE-003)
 */
@Component
@RequiredArgsConstructor
@JobExecutor(name = "gzRecycleNoShowTask")
public class GzRecycleNoShowJob {

    private final IGzRecycleAppointmentService recycleService;

    /**
     * SnailJob 触发入口。委托 service 扫过期 submitted 单 → 标 no_show。
     */
    public ExecuteResult jobExecute(JobArgs jobArgs) {
        try {
            int marked = recycleService.markExpiredNoShow();
            String msg = "回收 no_show 兜底完成：本轮标记 no_show " + marked + " 单";
            SnailJobLog.REMOTE.info("[GZ-RECYCLE-NO-SHOW] {}", msg);
            return ExecuteResult.success(msg);
        } catch (Exception ex) {
            SnailJobLog.REMOTE.error("[GZ-RECYCLE-NO-SHOW] 任务执行异常", ex);
            return ExecuteResult.failure("回收 no_show 任务异常：" + ex.getMessage());
        }
    }
}
