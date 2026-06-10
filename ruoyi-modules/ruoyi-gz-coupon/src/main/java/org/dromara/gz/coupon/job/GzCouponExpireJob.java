package org.dromara.gz.coupon.job;

import com.aizuda.snailjob.client.job.core.annotation.JobExecutor;
import com.aizuda.snailjob.client.job.core.dto.JobArgs;
import com.aizuda.snailjob.common.log.SnailJobLog;
import com.aizuda.snailjob.model.dto.ExecuteResult;
import lombok.RequiredArgsConstructor;
import org.dromara.gz.coupon.service.IGzUserCouponService;
import org.dromara.gz.coupon.service.IGzUserCouponService.CouponExpireResult;
import org.springframework.stereotype.Component;

/**
 * 用户券到期回收 cron 执行器（GZ-COUPON-002 AC 5，doc/11 §11.2 / doc/10 §12.N8）。
 *
 * <p><b>调度框架</b>：RuoYi-Vue-Plus 5.5.x 用 SnailJob（com.aizuda），不是 Quartz —— 本类是 SnailJob
 * 注解式执行器（{@code @JobExecutor}），<b>不</b>通过 Flyway INSERT {@code sys_job} 注册（仓库无 sys_job 表，
 * INSERT 会 hard-fail 拖垮启动）。job 名 / cron / 路由在 <b>SnailJob 服务端控制台</b>配置，执行器路由名对齐
 * 本类 {@code @JobExecutor(name)}。</p>
 *
 * <p><b>本类只是触发壳</b>：核心可测逻辑在 {@link IGzUserCouponService#expireBatch()}
 * （扫 {@code status='unused' AND expire_time < NOW()} → 批量推 expired，<b>locked 态不误伤</b>，
 * TenantHelper.ignore 全租户扫），单测脱离 SnailJob 直接测 service。</p>
 *
 * <p><b>SnailJob 控制台建议配置</b>（doc/11 §11.2 / 配置键 gz.coupon.expire.cron）：</p>
 * <ul>
 *   <li>任务名称：GZ-COUPON-EXPIRE</li>
 *   <li>执行器路由（executor_info）：{@code gzCouponExpireTask}（= 本类 @JobExecutor name）</li>
 *   <li>触发类型：CRON，表达式 {@code 0 0 3 * * ?}（每日凌晨 3 点扫一次；券过期非强实时，日级即可）</li>
 *   <li>任务参数（jobParams）：无（过期判定以 NOW() 为基准，service 内自取）</li>
 *   <li>阻塞策略：丢弃（默认）；组：{@code ruoyi_group}</li>
 * </ul>
 *
 * @author kevin-coder (sensenran-guzi · GZ-COUPON-002)
 */
@Component
@RequiredArgsConstructor
@JobExecutor(name = "gzCouponExpireTask")
public class GzCouponExpireJob {

    private final IGzUserCouponService userCouponService;

    public ExecuteResult jobExecute(JobArgs jobArgs) {
        try {
            CouponExpireResult result = userCouponService.expireBatch();
            String msg = String.format("用户券到期回收完成：扫描 %d / 过期 %d（locked 态未触碰）",
                result.scanned(), result.expired());
            SnailJobLog.REMOTE.info("[GZ-COUPON-EXPIRE] {}", msg);
            return ExecuteResult.success(msg);
        } catch (Exception ex) {
            SnailJobLog.REMOTE.error("[GZ-COUPON-EXPIRE] 任务执行异常", ex);
            return ExecuteResult.failure("用户券到期回收异常：" + ex.getMessage());
        }
    }
}
