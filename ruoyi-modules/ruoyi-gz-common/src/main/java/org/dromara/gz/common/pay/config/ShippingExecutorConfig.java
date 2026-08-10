package org.dromara.gz.common.pay.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 微信发货信息上报的<b>独立</b>异步线程池。
 *
 * <p><b>为什么必须独立</b>：上报任务里有一次 <b>12 秒的重试等待</b>
 * （{@code INSTANT_ATTEMPT_DELAYS_MS}，用来自愈微信订单索引未就绪的 {@code 10060001}），
 * 而它原先跑在 <b>ruoyi 默认的 {@code @Async} 池</b>里 —— 那个池 {@code core-size=8}、
 * 队列无界（无界队列意味着 {@code max-size} 永远不会被触发，<b>实际就是 8 个线程</b>），
 * 且被<b>全应用共享</b>：后台操作日志、登录日志、发货收口全在里面。</p>
 *
 * <p>后果是真实的：一次拼豆支付高峰里只要有 8 笔撞上 {@code 10060001}，池就被 8 个
 * 睡 12 秒的任务占满，之后所有异步任务排队 —— 操作日志/登录日志延迟入库；更要命的是
 * <b>发货收口（{@code settleShippingAsync}）被推迟数秒到数十秒</b>，直接把
 * 「上报回写 vs 追加包裹」的竞态窗口从毫秒级放大到秒级。</p>
 *
 * <p>拒绝策略用 {@code CallerRunsPolicy}（同扭蛋池）：发货上报宁可慢也不能丢 ——
 * 丢了就是微信侧永远看不到这个包裹，而 prod 没有 SnailJob 兜底扫描。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-JP-301)
 */
@Slf4j
@Configuration
public class ShippingExecutorConfig {

    /** 发货上报线程池 Bean 名（{@code @Async(SHIPPING_EXECUTOR)} 引用）。 */
    public static final String SHIPPING_EXECUTOR = "shippingUploadExecutor";

    @Bean(SHIPPING_EXECUTOR)
    public ThreadPoolTaskExecutor shippingUploadExecutor() {
        int cores = Runtime.getRuntime().availableProcessors();
        int max = Math.max(8, cores * 2);
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(max);
        // ★ 队列必须小：ThreadPoolTaskExecutor 是「队列没满就不开新线程」，
        //   队列给 500 等于 maxPoolSize 永远用不到，实际并发度恒为 core=4。
        //   而这个池里的任务会占线程睡 12 秒（10060001 自愈，代码注释说这是支付回调后的常态），
        //   4 并发 ÷ 12s ≈ 20 任务/分钟，收口任务排在后面要等几分钟到几十分钟。
        executor.setQueueCapacity(16);
        executor.setThreadNamePrefix("gz-shipping-");
        executor.setKeepAliveSeconds(60);
        // 队列满 → 调用线程同步跑（宁可慢不可丢；丢了微信侧永远看不到这个包裹）
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        // 优雅关闭：等在途上报跑完，别让重启把队列里的任务直接扔掉
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        log.info("[gz-shipping] 发货上报线程池就绪 core=4 max={} queue=16 reject=CallerRuns", max);
        return executor;
    }
}
