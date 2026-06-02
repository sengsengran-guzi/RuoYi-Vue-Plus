package org.dromara.gz.common.wechat.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.redis.utils.RedisUtils;
import org.dromara.gz.common.wechat.WxMiniappProperties;
import org.dromara.gz.common.wechat.WxPhoneAdapter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * 微信手机号 real 通道实现（getuserphonenumber）。
 *
 * <p>启用条件：{@code wx.miniapp.appid} 为非 wxMOCK 真实 AppID（与 {@link WxRealLoginAdapter} 同款
 * {@code @ConditionalOnExpression}，两通道互斥）。</p>
 *
 * <p>调用链（doc/10 §3.N6 手机号强收集）：</p>
 * <pre>
 * 1. access_token = cgi-bin/token（client_credential，appid + secret）— Redis 缓存复用
 * 2. POST wxa/business/getuserphonenumber?access_token=... body={code} → phone_info.purePhoneNumber
 * </pre>
 *
 * <p>access_token 全局缓存（key {@code wx:miniapp:access_token}，TTL = expires_in - 余量）：微信侧
 * access_token 有并发刷新限制（同一时刻只有一个有效），且与本应用其它微信能力（客服 / 订阅消息）共享，
 * 故集中在本类用 Redis 缓存。token 失效（errcode 40001/42001/40014）时强刷一次重试。</p>
 *
 * <p>不引入 weixin-java SDK（CLAUDE.md §6 #8 不发散依赖）；复用 {@link WxRealLoginAdapter} 同款 hutool
 * {@code HttpUtil} + {@code JSONUtil}。</p>
 *
 * @author kevin-coder (sensenran-guzi)
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnExpression("'${wx.miniapp.appid:wxMOCK}' != 'wxMOCK'")
public class WxRealPhoneAdapter implements WxPhoneAdapter {

    /** access_token 获取接口。 */
    private static final String ACCESS_TOKEN_URL = "https://api.weixin.qq.com/cgi-bin/token";

    /** 手机号获取接口。 */
    private static final String GET_PHONE_URL = "https://api.weixin.qq.com/wxa/business/getuserphonenumber";

    /** access_token Redis 缓存 key。 */
    private static final String ACCESS_TOKEN_CACHE_KEY = "wx:miniapp:access_token";

    /** HTTP 超时（毫秒）。 */
    private static final int HTTP_TIMEOUT_MS = 5000;

    /** access_token 提前过期余量（秒）— 避免取到临界刚过期的 token。 */
    private static final long TOKEN_EXPIRE_SAFETY_SECONDS = 300L;

    /** access_token 默认有效期（秒）— 微信侧通常返 7200，兜底用。 */
    private static final long TOKEN_DEFAULT_TTL_SECONDS = 7200L;

    private final WxMiniappProperties properties;

    @Override
    public String code2Phone(String code) {
        if (StrUtil.isBlank(code)) {
            throw new ServiceException("手机号授权 code 不能为空");
        }
        JSONObject resp = callGetPhone(code, getAccessToken(false));
        Integer errcode = resp.getInt("errcode");
        // token 失效 → 强刷一次重试（40001 invalid / 42001 expired / 40014 invalid token）
        if (errcode != null && (errcode == 40001 || errcode == 42001 || errcode == 40014)) {
            log.warn("[wx-phone] access_token 失效 errcode={}，强刷重试", errcode);
            resp = callGetPhone(code, getAccessToken(true));
        }
        return extractPhone(resp);
    }

    @Override
    public String channel() {
        return "real";
    }

    /** 调 getuserphonenumber，返回原始响应 JSON（错误码留给调用方判定，便于 token 重试）。 */
    private JSONObject callGetPhone(String code, String accessToken) {
        String url = GET_PHONE_URL + "?access_token=" + accessToken;
        String reqBody = JSONUtil.createObj().set("code", code).toString();
        String body;
        try {
            body = HttpUtil.createPost(url)
                .body(reqBody)
                .timeout(HTTP_TIMEOUT_MS)
                .execute()
                .body();
        } catch (Exception e) {
            log.error("[wx-phone] getuserphonenumber 网络异常", e);
            throw new ServiceException("微信侧网络异常，请稍后重试");
        }
        log.debug("[wx-phone] getuserphonenumber resp={}", body);
        return JSONUtil.parseObj(body);
    }

    /** 从 getuserphonenumber 响应解析明文手机号（errcode!=0 抛业务异常）。 */
    private String extractPhone(JSONObject json) {
        Integer errcode = json.getInt("errcode");
        if (errcode != null && errcode != 0) {
            log.warn("[wx-phone] getuserphonenumber 失败 errcode={} errmsg={}", errcode, json.getStr("errmsg"));
            throw new ServiceException("微信获取手机号失败: " + json.getStr("errmsg"));
        }
        JSONObject phoneInfo = json.getJSONObject("phone_info");
        if (phoneInfo == null) {
            throw new ServiceException("微信获取手机号失败：响应缺少 phone_info");
        }
        // purePhoneNumber 为不含国家码的号码（国内即 11 位）；缺失时回落 phoneNumber
        String phone = phoneInfo.getStr("purePhoneNumber");
        if (StrUtil.isBlank(phone)) {
            phone = phoneInfo.getStr("phoneNumber");
        }
        if (StrUtil.isBlank(phone)) {
            throw new ServiceException("微信获取手机号失败：手机号为空");
        }
        return phone;
    }

    /**
     * 取 access_token（Redis 缓存优先）。
     *
     * @param forceRefresh true 时跳过缓存强制重新拉取（token 失效重试场景）
     */
    private String getAccessToken(boolean forceRefresh) {
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
            log.error("[wx-phone] 获取 access_token 网络异常", e);
            throw new ServiceException("微信侧网络异常，请稍后重试");
        }
        JSONObject json = JSONUtil.parseObj(body);
        String token = json.getStr("access_token");
        if (StrUtil.isBlank(token)) {
            log.warn("[wx-phone] 获取 access_token 失败 errcode={} errmsg={}",
                json.getInt("errcode"), json.getStr("errmsg"));
            throw new ServiceException("微信获取 access_token 失败: " + json.getStr("errmsg"));
        }
        Integer expiresIn = json.getInt("expires_in");
        long ttl = (expiresIn != null ? expiresIn : TOKEN_DEFAULT_TTL_SECONDS) - TOKEN_EXPIRE_SAFETY_SECONDS;
        if (ttl < 60L) {
            ttl = 60L;
        }
        RedisUtils.setCacheObject(ACCESS_TOKEN_CACHE_KEY, token, Duration.ofSeconds(ttl));
        log.info("[wx-phone] access_token 刷新成功 ttl={}s", ttl);
        return token;
    }
}
