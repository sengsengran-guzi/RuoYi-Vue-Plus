package org.dromara.gz.common.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 本地补偿定时任务的 {@code @EnableScheduling} 开关（路 C）。
 *
 * <p><b>为什么单独需要它</b>：框架的 {@code @EnableScheduling} 挂在 {@code ruoyi-common-job} 的
 * {@code SnailJobConfig} 上，而后者是 {@code @ConditionalOnProperty(snail-job.enabled=true)} —— 未部署 SnailJob
 * （{@code SNAIL_JOB_ENABLED=false}）时该配置不加载，<b>整个应用没有任何 @Scheduled 会触发</b>。故本地补偿调度
 * （{@code GzLocalPayScheduler} / {@code GzLocalBeanScheduler}）必须自带一份独立于 SnailJob 的 {@code @EnableScheduling}。</p>
 *
 * <p><b>开关</b>：{@code gz.local-scheduler.enabled=true}（prod 默认 true）。与 SnailJob 的 {@code @EnableScheduling}
 * 并存无害（Spring 只注册一个调度处理器）；两者的 {@code @Scheduled}/{@code @JobExecutor} 各自按 flag 独立启停，
 * 部署 SnailJob 时把本 flag 关掉即可避免双跑。</p>
 *
 * @author kevin-coder (sensenran-guzi · 本地补偿调度)
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "gz.local-scheduler", name = "enabled", havingValue = "true")
public class GzLocalSchedulerConfig {
}
