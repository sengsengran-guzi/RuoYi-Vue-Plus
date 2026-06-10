package org.dromara.gz.common.pay.job;

import com.aizuda.snailjob.client.job.core.annotation.JobExecutor;
import com.aizuda.snailjob.client.job.core.dto.JobArgs;
import com.aizuda.snailjob.common.log.SnailJobLog;
import com.aizuda.snailjob.model.dto.ExecuteResult;
import lombok.RequiredArgsConstructor;
import org.dromara.gz.common.pay.service.IGzPayPayoutService;
import org.dromara.gz.common.pay.service.IGzPayPayoutService.QueryResult;
import org.springframework.stereotype.Component;

/**
 * 反向打款主动查单 cron 执行器（GZ-PAY-105 AC5，ADR-0006 §3 主动查单优先，doc/10 §14）。
 *
 * <p><b>调度框架</b>：RuoYi-Vue-Plus 5.5.x 用 SnailJob（com.aizuda），不是 Quartz —— 本类是 SnailJob
 * 注解式执行器（{@code @JobExecutor}），<b>不</b>通过 Flyway INSERT {@code sys_job} 注册（仓库无 sys_job
 * 表，INSERT 会启动 hard-fail，memory `scheduler-is-snailjob`）。job 名 / cron / 路由在 <b>SnailJob 控制台</b>
 * 配置，执行器路由名对齐本类 {@code @JobExecutor(name)}。注册参数见 reports/GZ-PAY-105.md §SnailJob 注册。</p>
 *
 * <p><b>本类只是触发壳</b>（AC5 无业务逻辑）：仅调 {@link IGzPayPayoutService#scanAndQuery()}（扫 processing
 * → 微信查单 → SUCCESS 推进 success / FAIL 推进 failed / PROCESSING 保持等下轮），把查单统计回写 SnailJob
 * 执行结果（sj_job_log）。核心可测逻辑全在 service，单测脱离 SnailJob server 直接测 service。
 * 权威样板：{@link PayOrderQueryJobExecutor}。</p>
 *
 * <p><b>SnailJob 控制台建议配置</b>（AC5）：</p>
 * <ul>
 *   <li>任务名称：GZ-PAY-PAYOUT-QUERY</li>
 *   <li>执行器路由（executor_info）：{@code gzPayoutQueryTask}（= 本类 @JobExecutor name）</li>
 *   <li>触发类型：CRON，表达式 {@code 0 0/2 * * * ?}（每 2 分钟；商家转账到账有延迟，频率低于支付查单即可，容器时区 Asia/Shanghai）</li>
 *   <li>阻塞策略：丢弃（DISCARD）— 上次未跑完不叠加</li>
 *   <li>组：{@code ruoyi_group}</li>
 * </ul>
 *
 * @author kevin-coder (sensenran-guzi · GZ-PAY-105)
 */
@Component
@RequiredArgsConstructor
@JobExecutor(name = "gzPayoutQueryTask")
public class GzPayoutQueryJob {

    private final IGzPayPayoutService payoutService;

    /**
     * SnailJob 触发入口。委托 service 扫 processing + 查单 + 推进，把统计回写执行结果。
     */
    public ExecuteResult jobExecute(JobArgs jobArgs) {
        try {
            QueryResult result = payoutService.scanAndQuery();
            String msg = String.format(
                "反向打款查单完成：扫描 %d / 到账 %d / 失败 %d / 保持 processing %d / 跳过 %d",
                result.scanned(), result.success(), result.failed(), result.pending(), result.skipped());
            SnailJobLog.REMOTE.info("[GZ-PAY-PAYOUT-QUERY] {}", msg);
            return ExecuteResult.success(msg);
        } catch (Exception ex) {
            SnailJobLog.REMOTE.error("[GZ-PAY-PAYOUT-QUERY] 任务执行异常", ex);
            return ExecuteResult.failure("反向打款查单任务异常：" + ex.getMessage());
        }
    }
}
