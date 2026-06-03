package org.dromara.gz.ord.job;

import com.aizuda.snailjob.client.job.core.annotation.JobExecutor;
import com.aizuda.snailjob.client.job.core.dto.JobArgs;
import com.aizuda.snailjob.common.log.SnailJobLog;
import com.aizuda.snailjob.model.dto.ExecuteResult;
import lombok.RequiredArgsConstructor;
import org.dromara.gz.ord.service.IGzOrdProductService;
import org.springframework.stereotype.Component;

/**
 * 预购商品截止下架 cron 执行器（GZ-ORD-101 AC 5，doc/10 §7.E1）。
 *
 * <p><b>调度框架</b>：RuoYi-Vue-Plus 5.5.x 用 SnailJob（com.aizuda），不是 Quartz —— 本类是 SnailJob
 * 注解式执行器（{@code @JobExecutor}），<b>不</b>通过 Flyway INSERT {@code sys_job}/{@code sj_job} 注册
 * （仓库无 {@code sys_job} 表，任何 {@code INSERT INTO sys_job} 的迁移会启动 hard-fail）。job 名 / cron
 * 表达式 / 路由策略在 <b>SnailJob 服务端控制台</b>配置，执行器路由名对齐本类 {@code @JobExecutor(name)}。
 * 注册步骤见 reports/GZ-ORD-101.md §SnailJob 控制台注册。</p>
 *
 * <p><b>本类只是触发壳</b>：核心可测逻辑全在 {@link IGzOrdProductService#autoOffExpiredProducts()}
 * （{@code UPDATE gz_ord_product SET status='auto_off' WHERE status='on_shelf' AND deadline_time<now()}，
 * 决策 D5：auto_off 唯一写入路径；幂等可重跑），单测脱离 SnailJob server 直接测 service 方法（AC 7 ⑤）。</p>
 *
 * <p><b>SnailJob 控制台建议配置</b>（ticket AC 5）：</p>
 * <ul>
 *   <li>任务名称：GZ-ORD-PRODUCT-DEADLINE</li>
 *   <li>执行器路由（executor_info）：{@code ordProductDeadlineJob}（= 本类 @JobExecutor name）</li>
 *   <li>触发类型：CRON，表达式 {@code 0 0 * * * ?}（每小时整点，容器时区 Asia/Shanghai）</li>
 *   <li>阻塞策略：丢弃（DISCARD）— 上次未跑完不叠加</li>
 *   <li>组：{@code ruoyi_group}</li>
 * </ul>
 *
 * <p><b>双重防线（R4）</b>：cron 每小时粒度 → 边界 1 小时内仍可能显示 on_shelf；ORD-103/104 下单时再
 * 校验 deadline_time（运行期 hard check）。本任务仅负责状态展示态自动转 auto_off。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-ORD-101)
 */
@Component
@RequiredArgsConstructor
@JobExecutor(name = "ordProductDeadlineJob")
public class OrdProductDeadlineJob {

    private final IGzOrdProductService productService;

    /**
     * SnailJob 触发入口。委托 service 把已过截止时间的 on_shelf 商品转 auto_off，回写执行结果消息。
     *
     * <p>异常处理（CLAUDE.md §6 #7）：兜底捕获 service 级异常 → {@code SnailJobLog.REMOTE.error} 远程
     * 日志 + 返回 {@code ExecuteResult.failure} 触发告警，不静默吞。</p>
     */
    public ExecuteResult jobExecute(JobArgs jobArgs) {
        try {
            int affected = productService.autoOffExpiredProducts();
            String msg = String.format("截止下架完成：本次自动下架 %d 个商品", affected);
            SnailJobLog.REMOTE.info("[GZ-ORD-PRODUCT-DEADLINE] {}", msg);
            return ExecuteResult.success(msg);
        } catch (Exception ex) {
            SnailJobLog.REMOTE.error("[GZ-ORD-PRODUCT-DEADLINE] 任务执行异常", ex);
            return ExecuteResult.failure("预购商品截止下架任务异常：" + ex.getMessage());
        }
    }
}
