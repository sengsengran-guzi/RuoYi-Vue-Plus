package org.dromara.gz.common.pay.job;

import com.aizuda.snailjob.client.job.core.annotation.JobExecutor;
import com.aizuda.snailjob.client.job.core.dto.JobArgs;
import com.aizuda.snailjob.common.log.SnailJobLog;
import com.aizuda.snailjob.model.dto.ExecuteResult;
import lombok.RequiredArgsConstructor;
import org.dromara.gz.common.pay.service.IGzPayShippingService;
import org.dromara.gz.common.pay.service.IGzPayShippingService.UploadStats;
import org.springframework.stereotype.Component;

/**
 * 微信发货信息上报兜底重试 cron 执行器（订单中心接入）。
 *
 * <p><b>定位</b>：支付回调内已落 {@code gz_pay_shipping_order} pending 行并触发 {@code @Async} 即时上报；
 * 本 job 是<b>兜底</b> —— 扫 48h 窗口内 pending/failed（即时上报漏掉 / 网络抖动失败 / 应用重启丢异步）的任务
 * 批量重试，保证每笔交易在 48h 内登记进微信订单中心（否则微信支付完成页提示「未接入购物订单」+ 扣体验分）。</p>
 *
 * <p><b>调度框架</b>：SnailJob（com.aizuda），非 Quartz —— 注解式执行器，<b>不</b>通过 Flyway INSERT
 * {@code sys_job}。job 名 / cron / 路由在 <b>SnailJob 控制台</b>配置（样板：GzPayExpireOrderJob）。</p>
 *
 * <p><b>SnailJob 控制台建议配置</b>：</p>
 * <ul>
 *   <li>任务名称：GZ-PAY-SHIPPING-UPLOAD</li>
 *   <li>执行器路由（executor_info）：{@code gzShippingUploadTask}（= 本类 @JobExecutor name）</li>
 *   <li>触发类型：CRON，表达式 {@code 0 0/2 * * * ?}（每 2 分钟，容器时区 Asia/Shanghai）</li>
 *   <li>阻塞策略：丢弃（DISCARD）— 上次未跑完不叠加</li>
 *   <li>组：{@code ruoyi_group}</li>
 * </ul>
 *
 * @author kevin-coder (sensenran-guzi)
 */
@Component
@RequiredArgsConstructor
@JobExecutor(name = "gzShippingUploadTask")
public class GzShippingUploadJob {

    private final IGzPayShippingService shippingService;

    /**
     * SnailJob 触发入口。委托 service 批量重试上报，把统计回写执行结果（sj_job_log）。
     */
    public ExecuteResult jobExecute(JobArgs jobArgs) {
        try {
            UploadStats stats = shippingService.uploadPending();
            String msg = String.format(
                "发货信息上报完成：扫描 %d / 成功 %d / 失败 %d",
                stats.scanned(), stats.success(), stats.failed());
            SnailJobLog.REMOTE.info("[GZ-PAY-SHIPPING-UPLOAD] {}", msg);
            return ExecuteResult.success(msg);
        } catch (Exception ex) {
            SnailJobLog.REMOTE.error("[GZ-PAY-SHIPPING-UPLOAD] 任务执行异常", ex);
            return ExecuteResult.failure("发货信息上报任务异常：" + ex.getMessage());
        }
    }
}
