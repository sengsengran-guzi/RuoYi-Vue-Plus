package org.dromara.gz.jp.service.internal;
import cn.dev33.satoken.stp.StpUtil;
import org.dromara.common.core.utils.ServletUtils;
import org.springframework.stereotype.Component;
import org.dromara.common.redis.utils.RedisUtils;
import org.redisson.api.RateType;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.gz.jp.exception.GzJpSnapSearchErrorCode;
@Component
public class GzJpSnapRateIdentity {
    public void checkDetail() {
        if (acquire() < 0)
            throw new ServiceException("搜得太快了，请稍后再试", GzJpSnapSearchErrorCode.SNAP_TOO_FAST);
    }
    protected long acquire() { return RedisUtils.rateLimiter("1001:jp:snap:detail-limit:" + key(), RateType.OVERALL, 30, 60, 86400); }
    public String key() { return StpUtil.isLogin() ? "user:" + StpUtil.getLoginIdAsString() : "ip:" + ServletUtils.getClientIP(); }
}
