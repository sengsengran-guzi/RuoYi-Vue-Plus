package org.dromara.gz.bean.job;

import cn.hutool.core.util.StrUtil;
import com.aizuda.snailjob.client.job.core.annotation.JobExecutor;
import com.aizuda.snailjob.client.job.core.dto.JobArgs;
import com.aizuda.snailjob.common.log.SnailJobLog;
import com.aizuda.snailjob.model.dto.ExecuteResult;
import lombok.RequiredArgsConstructor;
import org.dromara.gz.bean.service.IGzBeanBookingService;
import org.dromara.gz.bean.service.IGzBeanBookingService.ExpiredUnpaidResult;
import org.springframework.stereotype.Component;

/**
 * 拼豆付费 unpaid 占配额超时回收 cron 执行器（GZ-BEAN-014 AC 8，doc/10 §11.N13a）。
 *
 * <p><b>调度框架</b>：RuoYi-Vue-Plus 5.5.x 用 SnailJob（com.aizuda），不是 Quartz —— 本类是 SnailJob
 * 注解式执行器（{@code @JobExecutor}），<b>不</b>通过 Flyway INSERT {@code sys_job} 注册（hard-fail 风险）。
 * job 名 / cron / 路由在 <b>SnailJob 服务端控制台</b>配置，执行器路由名对齐本类 {@code @JobExecutor(name)}。</p>
 *
 * <p><b>本类只是触发壳</b>：核心可测逻辑在 {@link IGzBeanBookingService#markExpiredUnpaidBatch(int)}
 * （扫超时未付占位单 → 逐条 closePindou 释放配额 → 幂等 + 单条异常隔离 + 统计），单测脱离 SnailJob 直接测 service。</p>
 *
 * <p><b>SnailJob 控制台建议配置</b>（doc/10 §11.N13a + Q11.2）：</p>
 * <ul>
 *   <li>任务名称：GZ-BEAN-UNPAID-EXPIRE</li>
 *   <li>执行器路由（executor_info）：{@code gzBeanUnpaidExpireTask}（= 本类 @JobExecutor name）</li>
 *   <li>触发类型：CRON，表达式 {@code 0 0/5 * * * ?}（每 5 分钟，与微信 JSAPI 订单超时对齐扫描）</li>
 *   <li>任务参数（jobParams，可选）：超时分钟数（int），缺省 / 非法 → service 兜底 15 分钟</li>
 *   <li>阻塞策略：丢弃（默认）；组：{@code ruoyi_group}</li>
 * </ul>
 *
 * @author kevin-coder (sensenran-guzi · GZ-BEAN-014)
 */
@Component
@RequiredArgsConstructor
@JobExecutor(name = "gzBeanUnpaidExpireTask")
public class GzBeanUnpaidExpireJob {

    /** 缺省超时分钟数（jobParams 未传时，service 内也有同值兜底） */
    private static final int DEFAULT_TIMEOUT_MINUTES = 15;

    private final IGzBeanBookingService bookingService;

    public ExecuteResult jobExecute(JobArgs jobArgs) {
        try {
            int timeoutMinutes = parseTimeoutMinutes(jobArgs);
            ExpiredUnpaidResult result = bookingService.markExpiredUnpaidBatch(timeoutMinutes);
            String msg = String.format(
                "unpaid 超时回收完成（timeout=%dmin）：扫描 %d / 关闭 %d / 跳过 %d / 失败 %d",
                timeoutMinutes, result.scanned(), result.closed(), result.skipped(), result.failed());
            SnailJobLog.REMOTE.info("[GZ-BEAN-UNPAID-EXPIRE] {}", msg);
            if (result.failed() > 0) {
                SnailJobLog.REMOTE.error("[GZ-BEAN-UNPAID-EXPIRE] 存在关闭失败记录，需人工排查：{}", msg);
                return ExecuteResult.failure(msg);
            }
            return ExecuteResult.success(msg);
        } catch (Exception ex) {
            SnailJobLog.REMOTE.error("[GZ-BEAN-UNPAID-EXPIRE] 任务执行异常", ex);
            return ExecuteResult.failure("unpaid 超时回收异常：" + ex.getMessage());
        }
    }

    /** jobParams 解析超时分钟数（非法 / 缺省 → DEFAULT）。 */
    private int parseTimeoutMinutes(JobArgs jobArgs) {
        if (jobArgs == null) {
            return DEFAULT_TIMEOUT_MINUTES;
        }
        Object params = jobArgs.getJobParams();
        if (params == null || StrUtil.isBlank(params.toString())) {
            return DEFAULT_TIMEOUT_MINUTES;
        }
        try {
            int v = Integer.parseInt(params.toString().trim());
            return v > 0 ? v : DEFAULT_TIMEOUT_MINUTES;
        } catch (NumberFormatException ignored) {
            return DEFAULT_TIMEOUT_MINUTES;
        }
    }
}
