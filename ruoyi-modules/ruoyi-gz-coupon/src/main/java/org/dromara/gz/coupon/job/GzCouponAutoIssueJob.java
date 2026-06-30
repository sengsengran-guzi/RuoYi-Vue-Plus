package org.dromara.gz.coupon.job;

import com.aizuda.snailjob.client.job.core.annotation.JobExecutor;
import com.aizuda.snailjob.client.job.core.dto.JobArgs;
import com.aizuda.snailjob.common.log.SnailJobLog;
import com.aizuda.snailjob.model.dto.ExecuteResult;
import lombok.RequiredArgsConstructor;
import org.dromara.gz.coupon.service.IGzCouponIssuanceService;
import org.dromara.gz.coupon.service.IGzCouponIssuanceService.AutoIssueResult;
import org.springframework.stereotype.Component;

/**
 * 优惠券规则自动发放 cron 执行器（GZ-COUPON-003，doc/11 §优惠券域 / ADR-0010）。
 *
 * <p><b>调度框架</b>：RuoYi-Vue-Plus 5.5.x 用 SnailJob（com.aizuda），不是 Quartz —— 本类是 SnailJob
 * 注解式执行器（{@code @JobExecutor}），<b>不</b>通过 Flyway INSERT {@code sys_job} 注册（仓库无 sys_job 表，
 * INSERT 会 hard-fail 拖垮启动）。job 名 / cron / 路由在 <b>SnailJob 服务端控制台</b>配置，执行器路由名对齐
 * 本类 {@code @JobExecutor(name)}。</p>
 *
 * <p><b>本类只是触发壳</b>：核心可测逻辑在 {@link IGzCouponIssuanceService#autoIssueBatch()}
 * （全租户扫 {@code status='active' AND auto_issue=1 AND issue_strategy='filtered'} 模板 → 按 audience 条件
 * 圈人 → 去重已持券用户 → 配额乐观锁 + 批量发券，每模板独立事务隔离），单测脱离 SnailJob 直接测 service。</p>
 *
 * <p><b>SnailJob 控制台建议配置</b>：</p>
 * <ul>
 *   <li>任务名称：GZ-COUPON-AUTO-ISSUE</li>
 *   <li>执行器路由（executor_info）：{@code gzCouponAutoIssueTask}（= 本类 @JobExecutor name）</li>
 *   <li>触发类型：CRON，表达式 {@code 0 0/15 * * * ?}（每 15 分钟扫一次；filtered 条件命中即时性以分钟级补发即可）</li>
 *   <li>任务参数（jobParams）：无（扫描条件 service 内自取）</li>
 *   <li>阻塞策略：丢弃（默认）；组：{@code ruoyi_group}</li>
 * </ul>
 *
 * @author kevin-coder (sensenran-guzi · GZ-COUPON-003)
 */
@Component
@RequiredArgsConstructor
@JobExecutor(name = "gzCouponAutoIssueTask")
public class GzCouponAutoIssueJob {

    private final IGzCouponIssuanceService issuanceService;

    public ExecuteResult jobExecute(JobArgs jobArgs) {
        try {
            AutoIssueResult result = issuanceService.autoIssueBatch();
            String msg = String.format("优惠券自动发放完成：扫描 %d / 发放 %d",
                result.templatesScanned(), result.issued());
            SnailJobLog.REMOTE.info("[GZ-COUPON-AUTO-ISSUE] {}", msg);
            return ExecuteResult.success(msg);
        } catch (Exception ex) {
            SnailJobLog.REMOTE.error("[GZ-COUPON-AUTO-ISSUE] 任务执行异常", ex);
            return ExecuteResult.failure("优惠券自动发放异常：" + ex.getMessage());
        }
    }
}
