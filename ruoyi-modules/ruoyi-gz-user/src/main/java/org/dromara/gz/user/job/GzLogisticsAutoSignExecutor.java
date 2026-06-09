package org.dromara.gz.user.job;

import com.aizuda.snailjob.client.job.core.annotation.JobExecutor;
import com.aizuda.snailjob.client.job.core.dto.JobArgs;
import com.aizuda.snailjob.common.log.SnailJobLog;
import com.aizuda.snailjob.model.dto.ExecuteResult;
import lombok.RequiredArgsConstructor;
import org.dromara.gz.user.service.IGzLogisticsSignService;
import org.springframework.stereotype.Component;

/**
 * 跨境物流 7 天自动签收 cron 执行器（GZ-USER-104 AC2，doc/10 §9.N5）。
 *
 * <p><b>调度框架</b>：RuoYi-Vue-Plus 5.5.x 用 SnailJob（com.aizuda），不是 Quartz。本类是 SnailJob
 * 注解式执行器（{@code @JobExecutor}），<b>不</b>通过 Flyway INSERT {@code sys_job} 注册（仓库无 sys_job 表，
 * 任何 {@code INSERT INTO sys_job} 的迁移会启动 hard-fail）。job 名 / cron / 路由策略在 <b>SnailJob 服务端
 * 控制台</b>配置，执行器路由名对齐本类 {@code @JobExecutor(name)}。注册步骤见 reports/GZ-USER-104.md
 * §SnailJob 控制台注册。</p>
 *
 * <p><b>本类只是触发壳</b>：核心可测逻辑全在 {@link IGzLogisticsSignService#autoSign()}（扫 gz_ord_order +
 * gz_gacha_order 两表 in_china_dispatching 且 cn_dispatched_at 超 N 天 → delivered + 审计；行级条件 UPDATE
 * 幂等可重跑），单测脱离 SnailJob server 直接测 service（AC7）。</p>
 *
 * <p><b>SnailJob 控制台建议配置</b>（ticket AC2，写进 testing-human）：</p>
 * <ul>
 *   <li>任务名称：GZ-LOGISTICS-AUTO-SIGN</li>
 *   <li>执行器路由（executor_info）：{@code gzLogisticsAutoSignExecutor}（= 本类 @JobExecutor name）</li>
 *   <li>触发类型：CRON，表达式 {@code 0 0 * * * ?}（每小时整点，容器时区 Asia/Shanghai）</li>
 *   <li>阻塞策略：丢弃（DISCARD）— 上次未跑完不叠加</li>
 *   <li>组：{@code ruoyi_group}</li>
 * </ul>
 *
 * <p><b>分布式只跑一份（AC5）</b>：SnailJob 单节点调度触发；即便多节点重复触发，service 内行级条件
 * UPDATE（WHERE logistics_status='in_china_dispatching' + version）天然幂等，不双签，无需额外分布式锁。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-USER-104)
 */
@Component
@RequiredArgsConstructor
@JobExecutor(name = "gzLogisticsAutoSignExecutor")
public class GzLogisticsAutoSignExecutor {

    private final IGzLogisticsSignService signService;

    /**
     * SnailJob 触发入口。委托 service 自动签收，回写执行结果消息。
     *
     * <p>异常处理（CLAUDE.md §6 #7）：兜底捕获 service 级异常 → {@code SnailJobLog.REMOTE.error} 远程日志
     * + 返回 {@code ExecuteResult.failure} 触发告警，不静默吞。批内单订单失败由 service 自身 try-catch 吸收。</p>
     */
    public ExecuteResult jobExecute(JobArgs jobArgs) {
        try {
            int signed = signService.autoSign();
            String msg = String.format("自动签收完成：本次签收 %d 笔订单（预购 + 扭蛋两表）", signed);
            SnailJobLog.REMOTE.info("[GZ-LOGISTICS-AUTO-SIGN] {}", msg);
            return ExecuteResult.success(msg);
        } catch (Exception ex) {
            SnailJobLog.REMOTE.error("[GZ-LOGISTICS-AUTO-SIGN] 任务执行异常", ex);
            return ExecuteResult.failure("跨境物流自动签收任务异常：" + ex.getMessage());
        }
    }
}
