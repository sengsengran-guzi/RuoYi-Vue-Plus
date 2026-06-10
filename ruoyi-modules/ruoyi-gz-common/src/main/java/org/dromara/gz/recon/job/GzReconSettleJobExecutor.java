package org.dromara.gz.recon.job;

import com.aizuda.snailjob.client.job.core.annotation.JobExecutor;
import com.aizuda.snailjob.client.job.core.dto.JobArgs;
import com.aizuda.snailjob.common.log.SnailJobLog;
import com.aizuda.snailjob.model.dto.ExecuteResult;
import lombok.RequiredArgsConstructor;
import org.dromara.gz.recon.service.IGzReconBatchService;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * 对账季度结算跑批 cron 执行器（GZ-ADMIN-105，doc/10 §10.N4）。
 *
 * <p>SnailJob 薄壳，转调 {@link IGzReconBatchService#runQuarterly}（当季 3 月分成合计 + 月维护费 ×3）。
 * 当前月份所属季度由 {@link #currentQuarter} 推算。约束同 {@link GzReconDailyJobExecutor}（禁 sys_job INSERT）。</p>
 *
 * <p>控制台注册：任务名 GZ-RECON-SETTLE / 路由 gzReconSettleJob / CRON {@code 0 0 4 15 3,6,9,12 ?}
 * （季度末月 15 日 04:00）/ 阻塞 DISCARD / 组 ruoyi_group。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-ADMIN-105)
 */
@Component
@RequiredArgsConstructor
@JobExecutor(name = "gzReconSettleJob")
public class GzReconSettleJobExecutor {

    private final IGzReconBatchService batchService;

    public ExecuteResult jobExecute(JobArgs jobArgs) {
        try {
            String quarter = currentQuarter(LocalDate.now());
            int count = batchService.runQuarterly(quarter);
            String msg = String.format("对账季度结算跑批完成：quarter=%s，结算单 %d 条", quarter, count);
            SnailJobLog.REMOTE.info("[GZ-RECON-SETTLE] {}", msg);
            return ExecuteResult.success(msg);
        } catch (Exception ex) {
            SnailJobLog.REMOTE.error("[GZ-RECON-SETTLE] 跑批异常", ex);
            return ExecuteResult.failure("对账季度结算跑批异常：" + ex.getMessage());
        }
    }

    /** 当前日期所属季度标识 yyyy-Qn（n = (month-1)/3 + 1）。 */
    private static String currentQuarter(LocalDate date) {
        int q = (date.getMonthValue() - 1) / 3 + 1;
        return date.getYear() + "-Q" + q;
    }
}
