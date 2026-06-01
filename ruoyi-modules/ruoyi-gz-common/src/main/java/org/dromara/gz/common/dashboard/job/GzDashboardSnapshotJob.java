package org.dromara.gz.common.dashboard.job;

import com.aizuda.snailjob.client.job.core.annotation.JobExecutor;
import com.aizuda.snailjob.client.job.core.dto.JobArgs;
import com.aizuda.snailjob.common.log.SnailJobLog;
import com.aizuda.snailjob.model.dto.ExecuteResult;
import lombok.RequiredArgsConstructor;
import org.dromara.gz.common.dashboard.service.IGzDashboardService;
import org.dromara.gz.common.dashboard.service.IGzDashboardService.SnapshotResult;
import org.springframework.stereotype.Component;

/**
 * 数据看板快照 cron 执行器（GZ-ADMIN-003）。
 *
 * <p><b>调度框架</b>：RuoYi-Vue-Plus 5.5.x 用 SnailJob（com.aizuda），不是 Quartz —— 故本类是 SnailJob
 * 注解式执行器（{@code @JobExecutor}），<b>不</b>通过 Flyway INSERT {@code sys_job} 注册（仓库无该表，
 * 任何 INSERT 会启动 hard-fail）。job 名 / cron 表达式 / 路由策略在 <b>SnailJob 服务端控制台</b>（admin
 * 「定时任务」菜单）配置，执行器路由名对齐本类 {@code @JobExecutor(name)}。</p>
 *
 * <p><b>本类只是触发壳</b>：核心可测逻辑全在 {@link IGzDashboardService#takeSnapshot()}（5 个 metric
 * 计算 + 批量写快照 + 异常隔离），单测脱离 SnailJob server 直接测 service（AC 7）。</p>
 *
 * <p><b>SnailJob 控制台配置</b>（ticket AC 2 + reports §SnailJob 注册）：</p>
 * <ul>
 *   <li>任务名称：GZ-DASHBOARD-SNAPSHOT</li>
 *   <li>执行器路由（executor_info）：{@code gzDashboardSnapshotTask}（= 本类 @JobExecutor name）</li>
 *   <li>触发类型：CRON，表达式 {@code 0 0/5 * * * ?}（每 5 分钟，容器时区 Asia/Shanghai）</li>
 *   <li>阻塞策略：丢弃（DISCARD）— 上次未跑完不叠加 + 禁并发</li>
 *   <li>组：{@code ruoyi_group}</li>
 * </ul>
 *
 * @author kevin-coder (sensenran-guzi · GZ-ADMIN-003)
 */
@Component
@RequiredArgsConstructor
@JobExecutor(name = "gzDashboardSnapshotTask")
public class GzDashboardSnapshotJob {

    private final IGzDashboardService dashboardService;

    /**
     * SnailJob 触发入口。委托 service 计算 + 写快照，把结果回写执行结果消息（写入 sj_job_log）。
     *
     * <p>异常处理（AC 7 / CLAUDE.md §6 #7）：service.takeSnapshot 内部已 catch 不外抛；此处再据
     * {@code success} 决定 success/failure —— failure 触发 SnailJob 告警通道，不静默吞。</p>
     */
    public ExecuteResult jobExecute(JobArgs jobArgs) {
        SnapshotResult result = dashboardService.takeSnapshot();
        if (result.success()) {
            SnailJobLog.REMOTE.info("[GZ-DASHBOARD-SNAPSHOT] {}", result.message());
            return ExecuteResult.success(result.message());
        }
        SnailJobLog.REMOTE.error("[GZ-DASHBOARD-SNAPSHOT] 快照失败需排查：{}", result.message());
        return ExecuteResult.failure(result.message());
    }
}
