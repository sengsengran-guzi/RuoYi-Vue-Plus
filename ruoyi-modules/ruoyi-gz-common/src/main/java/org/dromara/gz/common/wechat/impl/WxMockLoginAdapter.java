package org.dromara.gz.common.wechat.impl;

import cn.hutool.core.util.StrUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.gz.common.wechat.WxAdapterDispatcher;
import org.dromara.gz.common.wechat.WxAppResolver;
import org.dromara.gz.common.wechat.WxJscode2SessionResult;
import org.dromara.gz.common.wechat.WxLoginAdapter;
import org.springframework.stereotype.Component;

/**
 * 微信登录 mock 通道实现。
 *
 * <p><b>装配</b>（ADR-0019 §1）：无条件注册；某个小程序的 {@code appid} 为空或 {@code wxMOCK} 时
 * 由 {@link WxAdapterDispatcher} 在运行时选中本实现 —— 新小程序走 mock 不再拖累现小程序的 real 通道。</p>
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
@RequiredArgsConstructor
public class WxMockLoginAdapter implements WxLoginAdapter {

    /** mock openid 前缀（便于日志快速识别）。 */
    private static final String MOCK_OPENID_PREFIX = "mock-";

    /** mock unionid 前缀。 */
    private static final String MOCK_UNIONID_PREFIX = "mock-union-";

    /** mock session_key 前缀。 */
    private static final String MOCK_SESSION_KEY_PREFIX = "mock-session-";

    /** code 取前 N 位用于派生 openid（足够区分，且短便于日志）。 */
    private static final int CODE_PREFIX_LEN = 8;

    private final WxAppResolver appResolver;

    @Override
    public WxJscode2SessionResult code2Session(String code) {
        if (StrUtil.isBlank(code)) {
            throw new ServiceException("微信登录 code 不能为空");
        }
        // dev 稳定 mock 用户：配了 mock-stable-openid 则忽略 code，恒返固定 openid（让本地换 code/续期仍是同一人）。
        // 按小程序取（各 app 可各配一个，否则两个小程序的 mock 用户 openid 相同、看起来像同一人）。
        String stable = appResolver.currentApp().getMockStableOpenid();
        String prefix = StrUtil.isNotBlank(stable)
            ? stable.trim()
            : (code.length() > CODE_PREFIX_LEN ? code.substring(0, CODE_PREFIX_LEN) : code);
        WxJscode2SessionResult result = WxJscode2SessionResult.builder()
            .openid(MOCK_OPENID_PREFIX + prefix)
            .unionid(MOCK_UNIONID_PREFIX + prefix)
            .sessionKey(MOCK_SESSION_KEY_PREFIX + code)
            .build();
        log.info("[wx-mock] code2Session code={} stable={} → openid={} unionid={}",
            code, StrUtil.isNotBlank(stable), result.getOpenid(), result.getUnionid());
        return result;
    }

    @Override
    public String channel() {
        return "mock";
    }
}
