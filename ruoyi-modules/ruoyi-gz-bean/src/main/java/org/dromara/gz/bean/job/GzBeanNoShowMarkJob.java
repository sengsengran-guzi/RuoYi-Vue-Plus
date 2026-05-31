package org.dromara.gz.bean.job;

import com.aizuda.snailjob.client.job.core.annotation.JobExecutor;
import com.aizuda.snailjob.client.job.core.dto.JobArgs;
import com.aizuda.snailjob.common.log.SnailJobLog;
import com.aizuda.snailjob.model.dto.ExecuteResult;
import lombok.RequiredArgsConstructor;
import org.dromara.gz.bean.service.IGzBeanBookingService;
import org.dromara.gz.bean.service.IGzBeanBookingService.NoShowMarkResult;
import org.springframework.stereotype.Component;

/**
 * 拼豆预约 no_show 自动标记 cron 执行器（GZ-BEAN-009，doc/10 §3.N13）。
 *
 * <p><b>调度框架</b>：RuoYi-Vue-Plus 5.5.x 用 SnailJob（com.aizuda），不是 Quartz —— 故本类是 SnailJob
 * 注解式执行器（{@code @JobExecutor}），<b>不</b>通过 Flyway INSERT {@code sys_job}/{@code sj_job} 注册。
 * job 名 / cron 表达式 / 路由策略在 <b>SnailJob 服务端控制台</b>（admin「定时任务」菜单）配置，执行器路由名
 * 对齐本类 {@code @JobExecutor(name)}。注册步骤见 reports/GZ-BEAN-009.md §控制台注册 + D05/testing-human.md。</p>
 *
 * <p><b>本类只是触发壳</b>：核心可测逻辑全在 {@link IGzBeanBookingService#markNoShowBatch()}
 * （扫昨日 pending → 条件 UPDATE 标 no_show → 写 booking_log → 幂等 + 单条异常隔离 + 统计），
 * 单测脱离 SnailJob server 直接测 service 方法（AC 6）。</p>
 *
 * <p><b>SnailJob 控制台建议配置</b>（doc/10 §3.N13 + ticket AC 1）：</p>
 * <ul>
 *   <li>任务名称：GZ-BEAN-NO-SHOW-MARK</li>
 *   <li>执行器路由（executor_info）：{@code gzBeanNoShowMarkTask}（= 本类 @JobExecutor name）</li>
 *   <li>触发类型：CRON，表达式 {@code 0 0 2 * * ?}（每日 02:00，容器时区 Asia/Shanghai）</li>
 *   <li>阻塞策略：丢弃（默认）— 上次未跑完不叠加</li>
 *   <li>组：{@code ruoyi_group}</li>
 * </ul>
 *
 * @author kevin-coder (sensenran-guzi · GZ-BEAN-009)
 */
@Component
@RequiredArgsConstructor
@JobExecutor(name = "gzBeanNoShowMarkTask")
public class GzBeanNoShowMarkJob {

    private final IGzBeanBookingService bookingService;

    /**
     * SnailJob 触发入口。委托 service 批量标记，把统计结果回写执行结果消息（写入 sj_job_log）。
     *
     * <p>异常处理（ticket AC 4 / CLAUDE.md §6 #7）：service 内单条失败已 catch 隔离；此处兜底捕获
     * service 级未预期异常 → 远程日志记录 + 返回 {@code ExecuteResult.failure}，触发 SnailJob 告警通道，
     * 不静默吞。失败数 > 0 时同样返回 failure 让调度中心标记本批次异常。</p>
     */
    public ExecuteResult jobExecute(JobArgs jobArgs) {
        try {
            NoShowMarkResult result = bookingService.markNoShowBatch();
            String msg = String.format(
                "no_show 标记完成：扫描 %d / 成功 %d / 跳过 %d / 失败 %d",
                result.scanned(), result.marked(), result.skipped(), result.failed());
            SnailJobLog.REMOTE.info("[GZ-BEAN-NO-SHOW-MARK] {}", msg);

            if (result.failed() > 0) {
                // 部分失败 → 标记本批次为 failure 触发告警，消息含统计便于排查
                SnailJobLog.REMOTE.error("[GZ-BEAN-NO-SHOW-MARK] 存在标记失败记录，需人工排查：{}", msg);
                return ExecuteResult.failure(msg);
            }
            return ExecuteResult.success(msg);
        } catch (Exception ex) {
            // service 级未预期异常（如全表扫描 SQL 异常）→ 不吞，记录 + failure 触发告警
            SnailJobLog.REMOTE.error("[GZ-BEAN-NO-SHOW-MARK] 任务执行异常", ex);
            return ExecuteResult.failure("no_show 标记任务异常：" + ex.getMessage());
        }
    }
}
