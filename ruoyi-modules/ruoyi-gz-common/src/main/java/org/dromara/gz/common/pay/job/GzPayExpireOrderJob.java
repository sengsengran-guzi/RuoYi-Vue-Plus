package org.dromara.gz.common.pay.job;

import com.aizuda.snailjob.client.job.core.annotation.JobExecutor;
import com.aizuda.snailjob.client.job.core.dto.JobArgs;
import com.aizuda.snailjob.common.log.SnailJobLog;
import com.aizuda.snailjob.model.dto.ExecuteResult;
import lombok.RequiredArgsConstructor;
import org.dromara.gz.common.pay.service.IGzPayTransactionService;
import org.dromara.gz.common.pay.service.IGzPayTransactionService.ExpireResult;
import org.springframework.stereotype.Component;

/**
 * 支付超时关单 cron 执行器（GZ-PAY-001 AC 8，doc/10 §2.E5）。
 *
 * <p><b>调度框架</b>：RuoYi-Vue-Plus 5.5.x 用 SnailJob（com.aizuda），不是 Quartz —— 本类是
 * SnailJob 注解式执行器（{@code @JobExecutor}），<b>不</b>通过 Flyway INSERT {@code sys_job} 注册
 * （仓库无 sys_job 表，INSERT 会启动 hard-fail）。job 名 / cron / 路由在 <b>SnailJob 控制台</b> 配置，
 * 执行器路由名对齐本类 {@code @JobExecutor(name)}。注册参数见 reports/GZ-PAY-001.md §SnailJob 注册。</p>
 *
 * <p><b>本类只是触发壳</b>：核心可测逻辑全在 {@link IGzPayTransactionService#expireTimeoutOrders()}
 * （扫 pending 且 expire_time &lt; now → 条件 UPDATE 标 timeout + 幂等 + 单条异常隔离），
 * 单测脱离 SnailJob server 直接测 service 方法（AC 10）。权威样板：GzBeanNoShowMarkJob。</p>
 *
 * <p><b>SnailJob 控制台建议配置</b>（AC 8）：</p>
 * <ul>
 *   <li>任务名称：GZ-PAY-EXPIRE-ORDER</li>
 *   <li>执行器路由（executor_info）：{@code gzPayExpireOrderTask}（= 本类 @JobExecutor name）</li>
 *   <li>触发类型：CRON，表达式 {@code 0 0/5 * * * ?}（每 5 分钟，容器时区 Asia/Shanghai）</li>
 *   <li>阻塞策略：丢弃（DISCARD）— 上次未跑完不叠加</li>
 *   <li>组：{@code ruoyi_group}</li>
 * </ul>
 *
 * @author kevin-coder (sensenran-guzi · GZ-PAY-001)
 */
@Component
@RequiredArgsConstructor
@JobExecutor(name = "gzPayExpireOrderTask")
public class GzPayExpireOrderJob {

    private final IGzPayTransactionService transactionService;

    /**
     * SnailJob 触发入口。委托 service 批量关单，把统计回写执行结果（sj_job_log）。
     */
    public ExecuteResult jobExecute(JobArgs jobArgs) {
        try {
            ExpireResult result = transactionService.expireTimeoutOrders();
            String msg = String.format(
                "超时关单完成：扫描 %d / 关单 %d / 跳过 %d",
                result.scanned(), result.closed(), result.skipped());
            SnailJobLog.REMOTE.info("[GZ-PAY-EXPIRE-ORDER] {}", msg);
            return ExecuteResult.success(msg);
        } catch (Exception ex) {
            SnailJobLog.REMOTE.error("[GZ-PAY-EXPIRE-ORDER] 任务执行异常", ex);
            return ExecuteResult.failure("超时关单任务异常：" + ex.getMessage());
        }
    }
}
