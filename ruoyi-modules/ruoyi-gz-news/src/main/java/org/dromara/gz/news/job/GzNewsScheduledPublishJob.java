package org.dromara.gz.news.job;

import com.aizuda.snailjob.client.job.core.annotation.JobExecutor;
import com.aizuda.snailjob.client.job.core.dto.JobArgs;
import com.aizuda.snailjob.common.log.SnailJobLog;
import com.aizuda.snailjob.model.dto.ExecuteResult;
import lombok.RequiredArgsConstructor;
import org.dromara.gz.news.service.IGzNewsArticleService;
import org.springframework.stereotype.Component;

/**
 * 资讯定时发布 cron 执行器（GZ-NEWS-004，doc/10 §5 定时发布分支）。
 *
 * <p><b>调度框架</b>：RuoYi-Vue-Plus 5.5.x 用 SnailJob（com.aizuda），不是 Quartz —— 故本类是 SnailJob
 * 注解式执行器（{@code @JobExecutor}），<b>不</b>通过 Flyway INSERT {@code sys_job}/{@code sj_job} 注册
 * （仓库无 {@code sys_job} 表，任何 {@code INSERT INTO sys_job} 的迁移会启动 hard-fail 拖垮整个 app）。
 * job 名 / cron 表达式 / 路由策略在 <b>SnailJob 服务端控制台</b>（admin「定时任务」菜单）配置，执行器
 * 路由名对齐本类 {@code @JobExecutor(name)}。注册步骤见 reports/GZ-NEWS-004.md §SnailJob 控制台注册。</p>
 *
 * <p><b>本类只是触发壳</b>：核心可测逻辑全在 {@link IGzNewsArticleService#publishDueArticles(int)}
 * （扫 due scheduled → 单事务批量 UPDATE 为 published，publish_time = schedule_publish_time，
 * 单批 100 / 总批 5 上限，幂等可重跑），单测脱离 SnailJob server 直接测 service 方法（AC 5）。</p>
 *
 * <p><b>SnailJob 控制台建议配置</b>（doc/10 §5 + ticket AC 4）：</p>
 * <ul>
 *   <li>任务名称：GZ-NEWS-PUBLISH-DUE</li>
 *   <li>执行器路由（executor_info）：{@code gzNewsScheduledPublishTask}（= 本类 @JobExecutor name）</li>
 *   <li>触发类型：CRON，表达式 {@code 0 * * * * ?}（每分钟第 0 秒，容器时区 Asia/Shanghai）</li>
 *   <li>阻塞策略：丢弃（DISCARD）— 上次未跑完不叠加（等价旧 Quartz misfire DO_NOTHING + 禁并发）</li>
 *   <li>组：{@code ruoyi_group}</li>
 * </ul>
 *
 * @author kevin-coder (sensenran-guzi · GZ-NEWS-004)
 */
@Component
@RequiredArgsConstructor
@JobExecutor(name = "gzNewsScheduledPublishTask")
public class GzNewsScheduledPublishJob {

    /** 单批上限（service 内部硬截断 100，此处传 100 表意一致；ticket D3）。 */
    private static final int BATCH_SIZE = 100;

    private final IGzNewsArticleService articleService;

    /**
     * SnailJob 触发入口。委托 service 批量发布到期定时文章，把处理结果回写执行结果消息（落 sj_job_log）。
     *
     * <p>异常处理（ticket AC 3 / CLAUDE.md §6 #7）：兜底捕获 service 级异常（如全表扫描 / 批量 UPDATE
     * SQL 异常）→ {@code SnailJobLog.REMOTE.error} 远程日志 + 返回 {@code ExecuteResult.failure} 触发
     * SnailJob 告警通道，标记本批次异常（落 sj_job_task_batch），不静默吞。</p>
     */
    public ExecuteResult jobExecute(JobArgs jobArgs) {
        try {
            int published = articleService.publishDueArticles(BATCH_SIZE);
            String msg = String.format("定时发布完成：本次发布 %d 篇", published);
            SnailJobLog.REMOTE.info("[GZ-NEWS-PUBLISH-DUE] {}", msg);
            return ExecuteResult.success(msg);
        } catch (Exception ex) {
            // service 级未预期异常 → 不吞，远程日志 + failure 触发告警
            SnailJobLog.REMOTE.error("[GZ-NEWS-PUBLISH-DUE] 任务执行异常", ex);
            return ExecuteResult.failure("资讯定时发布任务异常：" + ex.getMessage());
        }
    }
}
