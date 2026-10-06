package org.dromara.gz.jp.service.internal;
import org.dromara.common.redis.utils.RedisUtils;
import org.springframework.stereotype.Component;
import java.time.Duration;
@Component
public class GzJpSnapQuoteStore {
    public static final String PREFIX = "1001:jp:snap:quote:";
    public void put(String id, String value, Duration ttl) { RedisUtils.setCacheObject(PREFIX + id, value, ttl); }
    public String get(String id) { return RedisUtils.getCacheObject(PREFIX + id); }
}
