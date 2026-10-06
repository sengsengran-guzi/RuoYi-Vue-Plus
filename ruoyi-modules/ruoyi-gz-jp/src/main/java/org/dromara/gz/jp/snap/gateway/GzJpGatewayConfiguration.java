package org.dromara.gz.jp.snap.gateway;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class GzJpGatewayConfiguration {
    @Bean("snapPurchaseExecutor")
    public ThreadPoolTaskExecutor snapPurchaseExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(32);
        executor.setThreadNamePrefix("snap-purchase-");
        // Default AbortPolicy: overload must never fall back to the Web caller thread.
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(50);
        return executor;
    }
}
