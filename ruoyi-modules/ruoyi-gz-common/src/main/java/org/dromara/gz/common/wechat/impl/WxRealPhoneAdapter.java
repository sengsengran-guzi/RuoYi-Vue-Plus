package org.dromara.gz.common.wechat.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.gz.common.wechat.WxAccessTokenManager;
import org.dromara.gz.common.wechat.WxPhoneAdapter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

/**
 * 微信手机号 real 通道实现（getuserphonenumber）。
 *
 * <p>启用条件：{@code wx.miniapp.appid} 为非 wxMOCK 真实 AppID（与 {@link WxRealLoginAdapter} 同款
 * {@code @ConditionalOnExpression}，两通道互斥）。</p>
 *
 * <p>调用链（doc/10 §3.N6 手机号强收集）：</p>
 * <pre>
 * 1. access_token = {@link WxAccessTokenManager}（全局共享 Redis 缓存，cgi-bin/token）
 * 2. POST wxa/business/getuserphonenumber?access_token=... body={code} → phone_info.purePhoneNumber
 * </pre>
 *
 * <p>access_token 不再本类私有持有 —— 抽到 {@link WxAccessTokenManager} 统一管理（手机号 / 发货录入等
 * 共用一份，避免互顶失效）。token 失效（errcode 40001/42001/40014）时调 {@code getToken(true)} 强刷重试。</p>
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

    /** 手机号获取接口。 */
    private static final String GET_PHONE_URL = "https://api.weixin.qq.com/wxa/business/getuserphonenumber";

    /** HTTP 超时（毫秒）。 */
    private static final int HTTP_TIMEOUT_MS = 5000;

    private final WxAccessTokenManager accessTokenManager;

    @Override
    public String code2Phone(String code) {
        if (StrUtil.isBlank(code)) {
            throw new ServiceException("手机号授权 code 不能为空");
        }
        JSONObject resp = callGetPhone(code, accessTokenManager.getToken(false));
        Integer errcode = resp.getInt("errcode");
        // token 失效 → 强刷一次重试（40001 invalid / 42001 expired / 40014 invalid token）
        if (errcode != null && (errcode == 40001 || errcode == 42001 || errcode == 40014)) {
            log.warn("[wx-phone] access_token 失效 errcode={}，强刷重试", errcode);
            resp = callGetPhone(code, accessTokenManager.getToken(true));
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
}
