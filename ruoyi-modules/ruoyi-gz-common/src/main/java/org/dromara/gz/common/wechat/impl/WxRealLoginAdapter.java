package org.dromara.gz.common.wechat.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.gz.common.wechat.WxJscode2SessionResult;
import org.dromara.gz.common.wechat.WxLoginAdapter;
import org.dromara.gz.common.wechat.WxMiniappProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 微信登录 real 通道实现（jscode2session）。
 *
 * <p>启用条件：{@code wx.miniapp.appid} 配置了非 wxMOCK 的真实 AppID。
 * 用 SpEL 表达「非空且 != wxMOCK」— Spring Boot 原生 {@code @ConditionalOnProperty} 不支持 NOT 语义，
 * 因此 mock / real 两 adapter 用对偶写法：</p>
 * <ul>
 *   <li>Mock adapter：{@code @ConditionalOnProperty(name="wx.miniapp.appid", havingValue="wxMOCK", matchIfMissing=true)}
 *       — 值=wxMOCK 或 缺省 时启用</li>
 *   <li>Real adapter：{@code @ConditionalOnExpression("'${wx.miniapp.appid:wxMOCK}' != 'wxMOCK'")}
 *       — 值显式配置且 != wxMOCK 时启用</li>
 * </ul>
 *
 * <p>两条件互斥，启动期只会注入一个 {@link WxLoginAdapter} Bean，业务层零感知。</p>
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
@ConditionalOnExpression("'${wx.miniapp.appid:wxMOCK}' != 'wxMOCK'")
public class WxRealLoginAdapter implements WxLoginAdapter {

    /** 微信 jscode2session 接口 URL。 */
    private static final String JSCODE2SESSION_URL = "https://api.weixin.qq.com/sns/jscode2session";

    /** HTTP 超时（毫秒）。 */
    private static final int HTTP_TIMEOUT_MS = 5000;

    private final WxMiniappProperties properties;

    @Override
    public WxJscode2SessionResult code2Session(String code) {
        if (StrUtil.isBlank(code)) {
            throw new ServiceException("微信登录 code 不能为空");
        }
        Map<String, Object> params = new HashMap<>(4);
        params.put("appid", properties.getAppid());
        params.put("secret", properties.getSecret());
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
            log.warn("[wx-real] jscode2session errcode={} errmsg={}", errcode, errmsg);
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
