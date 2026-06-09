package org.dromara.gz.gacha.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 扭蛋开盒事务独立线程池（GZ-GACHA-104 强约束 #9 / 决策 D2 / 风险 R7）。
 *
 * <p><b>为什么独立线程池</b>：支付回调（{@code @Async("gachaExecutor")}）触发开盒事务，不占 ruoyi 默认
 * task executor（避免开盒慢事务拖垮其他异步任务）。微信 V3 回调要求 &lt; 3s 响应，故回调线程只入队
 * 不阻塞，开盒事务（SELECT FOR UPDATE + 多步 UPDATE）在本池异步执行。</p>
 *
 * <p><b>容量</b>（强约束 #9 / R7）：core=8、max=max(8, cores×2)、队列 1000。队列满 → {@code CallerRunsPolicy}
 * （退化为回调线程同步执行开盒，保证不丢单 —— 比丢弃安全；扭蛋付款必出货，宁可慢不可丢）。
 * 注：原 ticket 提「满走 RejectedExecutionHandler 入 DB 再异步消费」，本卡用 CallerRunsPolicy 兜底
 * （不丢单且无需额外 DB 队列表，符合 AC1 仅建 3 表的范围）；若压测发现队列频繁打满，再评估 ADR-0003 引 MQ。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-GACHA-104)
 */
@Slf4j
@Configuration
public class GachaExecutorConfig {

    /** 开盒事务异步线程池 Bean 名（{@code @Async("gachaExecutor")} 引用）。 */
    public static final String GACHA_EXECUTOR = "gachaExecutor";

    @Bean(GACHA_EXECUTOR)
    public ThreadPoolTaskExecutor gachaExecutor() {
        int cores = Runtime.getRuntime().availableProcessors();
        int max = Math.max(8, cores * 2);
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(8);
        executor.setMaxPoolSize(max);
        executor.setQueueCapacity(1000);
        executor.setThreadNamePrefix("gacha-draw-");
        executor.setKeepAliveSeconds(60);
        // 队列满 → 回调线程同步跑开盒（不丢单；扭蛋付款必出货，宁可慢不可丢，R7）
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        // 优雅关闭：等在途开盒事务跑完（防 COMMIT 中途被 kill 致钱扣了无货）
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        log.info("[gz-gacha] 开盒事务线程池就绪 core=8 max={} queue=1000 reject=CallerRuns", max);
        return executor;
    }
}
