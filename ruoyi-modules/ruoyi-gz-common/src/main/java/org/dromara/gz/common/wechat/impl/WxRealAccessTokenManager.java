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
import org.dromara.gz.common.wechat.WxAdapterDispatcher;
import org.dromara.gz.common.wechat.WxAppResolver;
import org.dromara.gz.common.wechat.WxMiniappProperties.MiniappApp;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * 微信小程序 access_token real 实现（cgi-bin/token，client_credential）。
 *
 * <p><b>装配</b>（ADR-0019 §1）：无条件注册，与 {@link WxMockAccessTokenManager} 同时在容器里，
 * 由 {@link WxAdapterDispatcher} 按当前请求 clientid 对应小程序的 mode 运行时选择。凭证
 * （appid / secret）按小程序取。</p>
 *
 * <p><b>缓存按 appid 隔离</b>（ADR-0019 §2）：key = {@code wx:miniapp:access_token:{appid}}，
 * 读取 / 刷新 / 失效重取三条路径一律用同一把 key。微信 {@code cgi-bin/token} 返回的 access_token 是
 * <b>appid 维度</b>凭证（两个小程序的 token 值不同且互不通用），共用一个 Redis 槽位会后刷新者覆盖先刷新者
 * → 手机号解密 / 发货上报<b>间歇性</b>返 40001 invalid credential（缓存竞争，重试可能又好了，极难定位；
 * 发货上报失败还直接影响资金结算）。</p>
 *
 * <p>同一 appid 内 token 仍是<b>全局单槽共享</b>（手机号 / 发货录入 / 客服等共用一份，TTL = expires_in - 余量）。
 * 原逻辑曾私有内嵌在 {@link WxRealPhoneAdapter}，发货信息录入（upload_shipping_info）也要 access_token，
 * 故抽到本管理器统一持有，杜绝<b>同一小程序内</b>两处各自刷新互顶失效 —— 按 appid 分槽只隔离不同小程序，
 * 不削弱这层共享。token 失效时调用方传 {@code forceRefresh=true} 强刷一次重试。</p>
 *
 * <p>不引入 weixin-java SDK（CLAUDE.md §6 #8 不发散依赖）；复用项目既有 hutool {@code HttpUtil} +
 * {@code JSONUtil}。</p>
 *
 * @author kevin-coder (sensenran-guzi)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WxRealAccessTokenManager implements WxAccessTokenManager {

    /** access_token 获取接口。 */
    private static final String ACCESS_TOKEN_URL = "https://api.weixin.qq.com/cgi-bin/token";

    /**
     * access_token Redis 缓存 key <b>前缀</b> —— 完整 key = 前缀 + 本小程序 appid（ADR-0019 §2）。
     *
     * <p>末尾的冒号是 key 的一部分，不要省：省掉会让 {@code wxAB} 与 {@code wxA} + {@code B} 这类
     * appid 撞进同一个槽位。同一 appid 内仍是单槽共享（手机号 / 发货录入 / 客服共用一份）。</p>
     */
    private static final String ACCESS_TOKEN_CACHE_KEY_PREFIX = "wx:miniapp:access_token:";

    /** HTTP 超时（毫秒）。 */
    private static final int HTTP_TIMEOUT_MS = 5000;

    /** access_token 提前过期余量（秒）— 避免取到临界刚过期的 token。 */
    private static final long TOKEN_EXPIRE_SAFETY_SECONDS = 300L;

    /** access_token 默认有效期（秒）— 微信侧通常返 7200，兜底用。 */
    private static final long TOKEN_DEFAULT_TTL_SECONDS = 7200L;

    private final WxAppResolver appResolver;

    @Override
    public String getToken(boolean forceRefresh) {
        return getTokenFor(null, forceRefresh);
    }

    /**
     * 取<b>指定小程序</b>的 access_token（发货信息上报专用）。
     *
     * <p>上报线程（{@code @Async} / cron / admin 补报）没有原请求上下文，{@link #getToken(boolean)} 只能落
     * {@code default-client-id} —— 多小程序下就是拿 A 的 token 报 B 的订单，微信恒回「支付单不存在」。
     * 故发货任务落库时记下归属 clientid，上报时经本方法取对应小程序的 token。</p>
     *
     * @param clientId     任务归属的小程序 clientid；blank / 未登记 → 回落当前请求或默认 app
     * @param forceRefresh 是否强制刷新（token 失效重试用）
     * @return access_token
     */
    public String getTokenFor(String clientId, boolean forceRefresh) {
        // 宽松口径：本能力也被 admin 后台手动补报 / @Async / cron 调用，那些线程带的 clientid
        // 不是小程序（如 plus-ui 的 PC clientid），认不出落默认 app（= 多 appid 改造前的行为）。
        // ★ 解析必须前置到读缓存之前：不知道自己是哪个小程序，就不知道该读哪个槽位（ADR-0019 §2）。
        MiniappApp app = appResolver.appOfOrDefault(clientId);
        String cacheKey = ACCESS_TOKEN_CACHE_KEY_PREFIX + app.getAppid();
        if (!forceRefresh) {
            String cached = RedisUtils.getCacheObject(cacheKey);
            if (StrUtil.isNotBlank(cached)) {
                return cached;
            }
        }
        Map<String, Object> params = new HashMap<>(4);
        params.put("grant_type", "client_credential");
        params.put("appid", app.getAppid());
        params.put("secret", app.getSecret());

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
            // 带上 clientid/appid：多小程序下「哪个小程序的凭证不对」是第一现场（40013 appid 错 / 40125 secret 错）
            log.warn("[wx-token] 获取 access_token 失败 clientid={} appid={} errcode={} errmsg={}",
                app.getClientId(), app.getAppid(), json.getInt("errcode"), json.getStr("errmsg"));
            throw new ServiceException("微信获取 access_token 失败: " + json.getStr("errmsg"));
        }
        Integer expiresIn = json.getInt("expires_in");
        long ttl = (expiresIn != null ? expiresIn : TOKEN_DEFAULT_TTL_SECONDS) - TOKEN_EXPIRE_SAFETY_SECONDS;
        if (ttl < 60L) {
            ttl = 60L;
        }
        RedisUtils.setCacheObject(cacheKey, token, Duration.ofSeconds(ttl));
        log.info("[wx-token] access_token 刷新成功 clientid={} appid={} key={} ttl={}s",
            app.getClientId(), app.getAppid(), cacheKey, ttl);
        return token;
    }
}
