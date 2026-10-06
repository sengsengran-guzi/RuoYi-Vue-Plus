package org.dromara.gz.jp.service.internal;
import org.dromara.common.redis.utils.RedisUtils;
import org.redisson.api.RLock;
import org.springframework.stereotype.Component;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
/** Shared Redis storage and cross-process single flight. Entries never contain customer data. */
@Component
public class GzJpSnapReadCache {
    public String get(String key) { return RedisUtils.getCacheObject("1001:jp:snap:read:" + key); }
    public void put(String key, String value, Duration ttl) { RedisUtils.setCacheObject("1001:jp:snap:read:" + key, value, ttl); }
    public AutoCloseable lock(String key) throws InterruptedException {
        RLock lock = RedisUtils.getClient().getLock("1001:jp:snap:flight:" + key);
        // Longer than the gateway's 3-second search budget; watchdog renews while translations finish.
        if (!lock.tryLock(10, TimeUnit.SECONDS)) throw new IllegalStateException("Search flight is busy");
        return () -> { if (lock.isHeldByCurrentThread()) lock.unlock(); };
    }
}
