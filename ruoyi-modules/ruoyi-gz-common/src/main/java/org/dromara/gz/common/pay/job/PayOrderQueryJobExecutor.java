package org.dromara.gz.common.pay.job;

import com.aizuda.snailjob.client.job.core.annotation.JobExecutor;
import com.aizuda.snailjob.client.job.core.dto.JobArgs;
import com.aizuda.snailjob.common.log.SnailJobLog;
import com.aizuda.snailjob.model.dto.ExecuteResult;
import lombok.RequiredArgsConstructor;
import org.dromara.gz.common.pay.service.IPayOrderQueryService;
import org.dromara.gz.common.pay.service.IPayOrderQueryService.ReconcileResult;
import org.springframework.stereotype.Component;

/**
 * 主动查单 + 补单 cron 执行器（GZ-PAY-102 AC 1，doc/10 §6.N7 主动查单兜底）。
 *
 * <p><b>调度框架</b>：RuoYi-Vue-Plus 5.5.x 用 SnailJob（com.aizuda），不是 Quartz —— 本类是
 * SnailJob 注解式执行器（{@code @JobExecutor}），<b>不</b>通过 Flyway INSERT {@code sys_job} 注册
 * （仓库无 sys_job 表，INSERT 会启动 hard-fail）。job 名 / cron / 路由在 <b>SnailJob 控制台</b> 配置，
 * 执行器路由名对齐本类 {@code @JobExecutor(name)}。注册参数见 reports/GZ-PAY-102.md §SnailJob 注册。</p>
 *
 * <p><b>本类只是触发壳</b>（AC1 无业务逻辑）：仅调 {@link IPayOrderQueryService#scanAndReconcile()}
 * （扫 pending → 微信 V3 查单 → SUCCESS 走 PAY-101 同一幂等补单路径 / NOTPAY 超 30min 关单 / CLOSED→closed
 * / PAYERROR→failed），把对账统计回写 SnailJob 执行结果（sj_job_log）。核心可测逻辑全在 service，
 * 单测脱离 SnailJob server 直接测 service 方法。权威样板：{@link GzPayExpireOrderJob}。</p>
 *
 * <p><b>SnailJob 控制台建议配置</b>（AC 1）：</p>
 * <ul>
 *   <li>任务名称：GZ-PAY-ORDER-QUERY</li>
 *   <li>执行器路由（executor_info）：{@code payOrderQueryJob}（= 本类 @JobExecutor name）</li>
 *   <li>触发类型：CRON，表达式 {@code 0 0/1 * * * ?}（每分钟，等价 0 *&#47;1 * * * ?，容器时区 Asia/Shanghai）</li>
 *   <li>阻塞策略：丢弃（DISCARD）— 上次未跑完不叠加</li>
 *   <li>组：{@code ruoyi_group}</li>
 * </ul>
 *
 * @author kevin-coder (sensenran-guzi · GZ-PAY-102)
 */
@Component
@RequiredArgsConstructor
@JobExecutor(name = "payOrderQueryJob")
public class PayOrderQueryJobExecutor {

    private final IPayOrderQueryService payOrderQueryService;

    /**
     * SnailJob 触发入口。委托 service 扫描 + 查单 + 补单，把统计回写执行结果。
     */
    public ExecuteResult jobExecute(JobArgs jobArgs) {
        try {
            ReconcileResult result = payOrderQueryService.scanAndReconcile();
            String msg = String.format(
                "主动查单完成：扫描 %d / 补单 %d / 关闭 %d / 失败 %d / 超时关单 %d / 保持 pending %d / 跳过 %d",
                result.scanned(), result.paid(), result.closed(), result.failed(),
                result.timeout(), result.pending(), result.skipped());
            SnailJobLog.REMOTE.info("[GZ-PAY-ORDER-QUERY] {}", msg);
            return ExecuteResult.success(msg);
        } catch (Exception ex) {
            SnailJobLog.REMOTE.error("[GZ-PAY-ORDER-QUERY] 任务执行异常", ex);
            return ExecuteResult.failure("主动查单任务异常：" + ex.getMessage());
        }
    }
}
