package org.dromara.gz.common.wechat.impl;

import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.gz.common.wechat.WxJscode2SessionResult;
import org.dromara.gz.common.wechat.WxLoginAdapter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 微信登录 mock 通道实现。
 *
 * <p>启用条件：{@code wx.miniapp.appid=wxMOCK}（含默认值场景；任务卡 AC 3 + D2）。
 * 用 {@code @ConditionalOnProperty.matchIfMissing=true} 保证 AppID 未配时也能默认走 mock。</p>
 *
 * <p>Mock 规则（契约 identical to real path）：</p>
 * <ul>
 *   <li>{@code openid  = "mock-" + code 前 8 位}</li>
 *   <li>{@code unionid = "mock-union-" + code 前 8 位}</li>
 *   <li>{@code sessionKey = "mock-session-" + code}（足够 mp 侧 testing-human 比对，且后端 Redis
 *       缓存策略不变）</li>
 * </ul>
 *
 * <p>同一 code 多次调用结果稳定（便于 Kevin testing-human 复测），但 sensenran C 端是公开注册，
 * 测试时换 code 即可拿到不同 openid，避免被「同一 mock 用户复用」误导业务断言。</p>
 *
 * <p>关联文档：doc/10 §1.N4 / GZ-SYS-002 AC 3 / dongjiaoshan 同款实现参考 WechatLoginServiceImpl.jscode2openid</p>
 *
 * @author kevin-coder (sensenran-guzi)
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "wx.miniapp", name = "appid", havingValue = "wxMOCK", matchIfMissing = true)
public class WxMockLoginAdapter implements WxLoginAdapter {

    /** mock openid 前缀（便于日志快速识别）。 */
    private static final String MOCK_OPENID_PREFIX = "mock-";

    /** mock unionid 前缀。 */
    private static final String MOCK_UNIONID_PREFIX = "mock-union-";

    /** mock session_key 前缀。 */
    private static final String MOCK_SESSION_KEY_PREFIX = "mock-session-";

    /** code 取前 N 位用于派生 openid（足够区分，且短便于日志）。 */
    private static final int CODE_PREFIX_LEN = 8;

    @Override
    public WxJscode2SessionResult code2Session(String code) {
        if (StrUtil.isBlank(code)) {
            throw new ServiceException("微信登录 code 不能为空");
        }
        String prefix = code.length() > CODE_PREFIX_LEN ? code.substring(0, CODE_PREFIX_LEN) : code;
        WxJscode2SessionResult result = WxJscode2SessionResult.builder()
            .openid(MOCK_OPENID_PREFIX + prefix)
            .unionid(MOCK_UNIONID_PREFIX + prefix)
            .sessionKey(MOCK_SESSION_KEY_PREFIX + code)
            .build();
        log.info("[wx-mock] code2Session code={} → openid={} unionid={}",
            code, result.getOpenid(), result.getUnionid());
        return result;
    }

    @Override
    public String channel() {
        return "mock";
    }
}
