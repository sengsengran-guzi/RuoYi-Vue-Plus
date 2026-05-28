package org.dromara.gz.common.wechat.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.redis.utils.RedisUtils;
import org.dromara.gz.common.wechat.SessionKeyStore;
import org.dromara.gz.common.wechat.WxMiniappProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * {@link SessionKeyStore} 的 Redis 实现（生产 / dev 默认）。
 *
 * <p>底层走 ruoyi {@link RedisUtils}（基于 Redisson）。Key 形如 {@code wx_session_key:{openid}}。
 * TTL 由 {@link WxMiniappProperties#getSessionKeyTtlSeconds()} 控制（默认 24h）。</p>
 *
 * <p>异常容忍：Redis 异常时静默 catch 不抛 — session_key 缓存丢失只影响"拼豆获取手机号"
 * 解密失败一次（重新走 wx.login 即可，doc/10 §1.E3 已设计为静默重试），不阻断登录主流程。</p>
 *
 * @author kevin-coder (sensenran-guzi)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisSessionKeyStore implements SessionKeyStore {

    /** Redis Key 前缀（与 dongjiaoshan / ruoyi 风格一致）。 */
    private static final String KEY_PREFIX = "wx_session_key:";

    private final WxMiniappProperties properties;

    @Override
    public void put(String openid, String sessionKey) {
        if (openid == null || sessionKey == null) {
            return;
        }
        try {
            RedisUtils.setCacheObject(
                KEY_PREFIX + openid,
                sessionKey,
                Duration.ofSeconds(properties.getSessionKeyTtlSeconds()));
        } catch (Exception e) {
            // doc/10 §1.E3：session_key 丢失允许静默 — 后续拼豆获取手机号时会重新换 session_key
            log.warn("[session-key-store] redis put 失败 openid={}（不阻断登录主流程）", openid, e);
        }
    }

    @Override
    public Optional<String> get(String openid) {
        if (openid == null) {
            return Optional.empty();
        }
        try {
            String value = RedisUtils.getCacheObject(KEY_PREFIX + openid);
            return Optional.ofNullable(value);
        } catch (Exception e) {
            log.warn("[session-key-store] redis get 失败 openid={}", openid, e);
            return Optional.empty();
        }
    }
}
