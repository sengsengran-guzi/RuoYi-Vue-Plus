package org.dromara.gz.common.wechat.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.redis.utils.RedisUtils;
import org.dromara.gz.common.wechat.WxAccessTokenManager;
import org.dromara.gz.common.wechat.WxMiniappProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * 微信小程序 access_token real 实现（cgi-bin/token，client_credential）。
 *
 * <p>启用条件：{@code wx.miniapp.appid} 为非 wxMOCK 真实 AppID（与 {@link WxRealLoginAdapter} /
 * {@link WxRealPhoneAdapter} 同款 {@code @ConditionalOnExpression}，real 通道互斥唯一）。</p>
 *
 * <p>token 全局缓存（key {@code wx:miniapp:access_token}，TTL = expires_in - 余量）。原逻辑曾私有内嵌在
 * {@link WxRealPhoneAdapter}，发货信息录入（upload_shipping_info）也要 access_token，故抽到本管理器统一
 * 持有，杜绝两处各自刷新互顶失效。token 失效时调用方传 {@code forceRefresh=true} 强刷一次重试。</p>
 *
 * <p>不引入 weixin-java SDK（CLAUDE.md §6 #8 不发散依赖）；复用项目既有 hutool {@code HttpUtil} +
 * {@code JSONUtil}。</p>
 *
 * @author kevin-coder (sensenran-guzi)
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnExpression("'${wx.miniapp.appid:wxMOCK}' != 'wxMOCK'")
public class WxRealAccessTokenManager implements WxAccessTokenManager {

    /** access_token 获取接口。 */
    private static final String ACCESS_TOKEN_URL = "https://api.weixin.qq.com/cgi-bin/token";

    /** access_token Redis 缓存 key（全局共享：手机号 / 发货录入 / 客服等共用一份）。 */
    private static final String ACCESS_TOKEN_CACHE_KEY = "wx:miniapp:access_token";

    /** HTTP 超时（毫秒）。 */
    private static final int HTTP_TIMEOUT_MS = 5000;

    /** access_token 提前过期余量（秒）— 避免取到临界刚过期的 token。 */
    private static final long TOKEN_EXPIRE_SAFETY_SECONDS = 300L;

    /** access_token 默认有效期（秒）— 微信侧通常返 7200，兜底用。 */
    private static final long TOKEN_DEFAULT_TTL_SECONDS = 7200L;

    private final WxMiniappProperties properties;

    @Override
    public String getToken(boolean forceRefresh) {
        if (!forceRefresh) {
            String cached = RedisUtils.getCacheObject(ACCESS_TOKEN_CACHE_KEY);
            if (StrUtil.isNotBlank(cached)) {
                return cached;
            }
        }
        Map<String, Object> params = new HashMap<>(4);
        params.put("grant_type", "client_credential");
        params.put("appid", properties.getAppid());
        params.put("secret", properties.getSecret());

        String body;
        try {
            body = HttpUtil.createGet(ACCESS_TOKEN_URL)
                .form(params)
                .timeout(HTTP_TIMEOUT_MS)
                .execute()
                .body();
        } catch (Exception e) {
            log.error("[wx-token] 获取 access_token 网络异常", e);
            throw new ServiceException("微信侧网络异常，请稍后重试");
        }
        JSONObject json = JSONUtil.parseObj(body);
        String token = json.getStr("access_token");
        if (StrUtil.isBlank(token)) {
            log.warn("[wx-token] 获取 access_token 失败 errcode={} errmsg={}",
                json.getInt("errcode"), json.getStr("errmsg"));
            throw new ServiceException("微信获取 access_token 失败: " + json.getStr("errmsg"));
        }
        Integer expiresIn = json.getInt("expires_in");
        long ttl = (expiresIn != null ? expiresIn : TOKEN_DEFAULT_TTL_SECONDS) - TOKEN_EXPIRE_SAFETY_SECONDS;
        if (ttl < 60L) {
            ttl = 60L;
        }
        RedisUtils.setCacheObject(ACCESS_TOKEN_CACHE_KEY, token, Duration.ofSeconds(ttl));
        log.info("[wx-token] access_token 刷新成功 ttl={}s", ttl);
        return token;
    }
}
