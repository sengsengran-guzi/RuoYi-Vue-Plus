package org.dromara.gz.common.wechat.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.gz.common.wechat.WxAdapterDispatcher;
import org.dromara.gz.common.wechat.WxAppResolver;
import org.dromara.gz.common.wechat.WxJscode2SessionResult;
import org.dromara.gz.common.wechat.WxLoginAdapter;
import org.dromara.gz.common.wechat.WxMiniappProperties.MiniappApp;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 微信登录 real 通道实现（jscode2session）。
 *
 * <p><b>装配</b>（ADR-0019 §1）：无条件注册。real / mock 不再由启动期条件 Bean 全局互斥 —— 本类与
 * {@link WxMockLoginAdapter} 同时在容器里，由 {@link WxAdapterDispatcher} 按当前请求 clientid 对应的
 * 小程序 mode 运行时选择。业务层注入的是 {@code @Primary} 的 dispatcher 门面，零感知。</p>
 *
 * <p><b>凭证按小程序取</b>：appid / secret 来自 {@link WxAppResolver#currentApp()}，不再读全局标量 ——
 * 两个小程序各用各的 secret 调 jscode2session（用错 appid 微信直接返 {@code invalid code}）。</p>
 *
 * <p>调用微信开放接口：</p>
 * <pre>
 * GET https://api.weixin.qq.com/sns/jscode2session?
 *     appid={WX_MA_APPID}&secret={WX_MA_SECRET}&js_code={code}&grant_type=authorization_code
 * </pre>
 *
 * <p>不引入新依赖（避免依赖发散，符合 CLAUDE.md §6 #8）；用 hutool 自带的 {@code HttpUtil} +
 * {@code JSONUtil}。后续如要换 weixin-java-miniapp SDK（GZ-PAY-001 引入时一并），把本类
 * 替换为 SDK 调用即可，{@link WxLoginAdapter} 接口不变。</p>
 *
 * <p>关联文档：doc/10 §1.N4 / GZ-SYS-002 D1 决策 / doc/11 §2.1 字段 openid/unionid/session_key</p>
 *
 * @author kevin-coder (sensenran-guzi)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WxRealLoginAdapter implements WxLoginAdapter {

    /** 微信 jscode2session 接口 URL。 */
    private static final String JSCODE2SESSION_URL = "https://api.weixin.qq.com/sns/jscode2session";

    /** HTTP 超时（毫秒）。 */
    private static final int HTTP_TIMEOUT_MS = 5000;

    private final WxAppResolver appResolver;

    @Override
    public WxJscode2SessionResult code2Session(String code) {
        if (StrUtil.isBlank(code)) {
            throw new ServiceException("微信登录 code 不能为空");
        }
        MiniappApp app = appResolver.currentApp();
        Map<String, Object> params = new HashMap<>(4);
        params.put("appid", app.getAppid());
        params.put("secret", app.getSecret());
        params.put("js_code", code);
        params.put("grant_type", "authorization_code");

        String body;
        try {
            body = HttpUtil.createGet(JSCODE2SESSION_URL)
                .form(params)
                .timeout(HTTP_TIMEOUT_MS)
                .execute()
                .body();
        } catch (Exception e) {
            log.error("[wx-real] jscode2session 网络异常 code={}", code, e);
            throw new ServiceException("微信侧网络异常，请稍后重试");
        }

        log.debug("[wx-real] jscode2session response body={}", body);
        JSONObject json = JSONUtil.parseObj(body);
        Integer errcode = json.getInt("errcode");
        if (errcode != null && errcode != 0) {
            String errmsg = json.getStr("errmsg");
            // 带上 appid：多小程序下「用错 appid/secret」的表现就是 invalid code，不打 appid 无从分辨是哪个小程序
            log.warn("[wx-real] jscode2session errcode={} errmsg={} clientid={} appid={}",
                errcode, errmsg, app.getClientId(), app.getAppid());
            throw new ServiceException("微信登录失败: " + errmsg);
        }

        return WxJscode2SessionResult.builder()
            .openid(json.getStr("openid"))
            .sessionKey(json.getStr("session_key"))
            .unionid(json.getStr("unionid"))   // 可能 null
            .build();
    }

    @Override
    public String channel() {
        return "real";
    }
}
