package org.dromara.gz.common.pay.job;

import com.aizuda.snailjob.client.job.core.annotation.JobExecutor;
import com.aizuda.snailjob.client.job.core.dto.JobArgs;
import com.aizuda.snailjob.common.log.SnailJobLog;
import com.aizuda.snailjob.model.dto.ExecuteResult;
import lombok.RequiredArgsConstructor;
import org.dromara.gz.common.pay.service.PayFundflowBillService;
import org.dromara.gz.common.pay.service.PayFundflowBillService.ReconcileResult;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * 资金账单对账批 cron 执行器（GZ-PAY-104 AC 2，doc/10 §10 E6 / doc/11 F4.3 / F9.2）。
 *
 * <p><b>调度框架</b>：RuoYi-Vue-Plus 5.5.x 用 SnailJob（com.aizuda），不是 Quartz —— 本类是
 * SnailJob 注解式执行器（{@code @JobExecutor}），<b>不</b>通过 Flyway INSERT {@code sys_job} 注册
 * （仓库无 sys_job 表，INSERT 会启动 hard-fail）。job 名 / cron / 路由在 <b>SnailJob 控制台</b> 配置，
 * 执行器路由名对齐本类 {@code @JobExecutor(name)}。注册参数见 reports/GZ-PAY-104.md §SnailJob 注册。</p>
 *
 * <p><b>本类只是触发壳</b>（AC2 无业务逻辑）：取 bizDate（默认前一业务日，北京时间，账单 T+1 可用）→
 * 调 {@link PayFundflowBillService#reconcileFee(LocalDate)} → 把对账统计回写 SnailJob 执行结果
 * （sj_job_log）。核心可测逻辑全在 service，单测脱离 SnailJob server 直接测 service 方法。
 * 权威样板：{@link GzPayExpireOrderJob}。</p>
 *
 * <p><b>SnailJob 控制台建议配置</b>（AC 2）：</p>
 * <ul>
 *   <li>任务名称：GZ-PAY-FUNDFLOW-BILL</li>
 *   <li>执行器路由（executor_info）：{@code gzPayFundflowBillJob}（= 本类 @JobExecutor name）</li>
 *   <li>触发类型：CRON，表达式 {@code 0 30 3 * * ?}（凌晨 3:30，账单 T+1 已生成，容器时区 Asia/Shanghai）</li>
 *   <li>阻塞策略：丢弃（DISCARD）— 上次未跑完不叠加</li>
 *   <li>组：{@code ruoyi_group}</li>
 * </ul>
 *
 * @author kevin-coder (sensenran-guzi · GZ-PAY-104)
 */
@Component
@RequiredArgsConstructor
@JobExecutor(name = "gzPayFundflowBillJob")
public class PayFundflowBillJob {

    private final PayFundflowBillService fundflowBillService;

    /**
     * SnailJob 触发入口。委托 service 拉账单 + 回写 fee_cent，把对账统计回写执行结果。
     *
     * <p>bizDate = 前一业务日（账单 T+1 可用）。两类对不上（孤儿账单 / 缺账）计数 &gt; 0 时，
     * 执行结果文案带 warning 标记（service 已 log.warn 含明细），便于控制台一眼识别。</p>
     */
    public ExecuteResult jobExecute(JobArgs jobArgs) {
        LocalDate bizDate = LocalDate.now().minusDays(1);
        try {
            ReconcileResult result = fundflowBillService.reconcileFee(bizDate);
            String msg = String.format(
                "资金账单对账完成 bill_date=%s：账单 %d / 回写 %d / 孤儿账单 %d / 缺账 %d",
                result.billDate(), result.billTotal(), result.matched(),
                result.transactionNotFoundCount(), result.paidNoBillCount());
            if (result.hasMismatch()) {
                SnailJobLog.REMOTE.warn("[GZ-PAY-FUNDFLOW-BILL] {}（存在对不上，明细见应用日志）", msg);
                return ExecuteResult.success("WARN " + msg);
            }
            SnailJobLog.REMOTE.info("[GZ-PAY-FUNDFLOW-BILL] {}", msg);
            return ExecuteResult.success(msg);
        } catch (Exception ex) {
            SnailJobLog.REMOTE.error("[GZ-PAY-FUNDFLOW-BILL] 任务执行异常 bill_date={}", bizDate, ex);
            return ExecuteResult.failure("资金账单对账异常 bill_date=" + bizDate + "：" + ex.getMessage());
        }
    }
}
