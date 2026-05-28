package org.dromara.gz.common.wechat;

import java.util.Optional;

/**
 * 微信 session_key 缓存。
 *
 * <p>doc/10 §1.N6：session_key 写 Redis（TTL 24h，由 {@link WxMiniappProperties#getSessionKeyTtlSeconds()} 控制）；
 * doc/10 §1.E3：拼豆获取手机号时若 session_key 过期，静默重走 wx.login。</p>
 *
 * <p>Key 命名：{@code wx_session_key:{openid}}（与 dongjiaoshan / ruoyi 风格一致；后续 GZ-COMMON-REDIS-KEYS
 * ticket 集中到常量类时迁移）。</p>
 *
 * <p>本接口隔离 Redis 静态调用便于单测 mock — 默认实现 {@link org.dromara.gz.common.wechat.impl.RedisSessionKeyStore}
 * 走 ruoyi {@code RedisUtils}；测试场景可注入 {@code InMemorySessionKeyStore}。</p>
 *
 * @author kevin-coder (sensenran-guzi)
 */
public interface SessionKeyStore {

    /** 写入 / 覆盖 openid 的 session_key（TTL 由实现层控制）。 */
    void put(String openid, String sessionKey);

    /** 取 openid 的 session_key（不存在 / 过期返 empty）。 */
    Optional<String> get(String openid);
}
