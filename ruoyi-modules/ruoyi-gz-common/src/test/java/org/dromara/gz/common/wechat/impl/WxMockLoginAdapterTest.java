package org.dromara.gz.common.wechat.impl;

import org.dromara.common.core.exception.ServiceException;
import org.dromara.gz.common.wechat.WxJscode2SessionResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link WxMockLoginAdapter} 单测 — 验证 AC 3 mock 通道契约。
 *
 * <p>覆盖：</p>
 * <ul>
 *   <li>code 非空 → openid/unionid 含前 8 位 + session_key 含全 code</li>
 *   <li>同 code 多次调用结果稳定（idempotent）</li>
 *   <li>code 短于 8 位 → openid 直接用全 code 派生（不越界）</li>
 *   <li>code 为空 / null → ServiceException</li>
 *   <li>channel() == "mock"</li>
 * </ul>
 *
 * @author kevin-coder (sensenran-guzi)
 */
@Tag("dev")
class WxMockLoginAdapterTest {

    private final WxMockLoginAdapter adapter = new WxMockLoginAdapter();

    @Test
    @DisplayName("AC 3: 标准 code → 派生 mock-{前8位} / mock-union-{前8位} / mock-session-{全 code}")
    void code2Session_standardCode_returnsDerivedFields() {
        String code = "abc12345xyz999000";
        WxJscode2SessionResult result = adapter.code2Session(code);

        assertEquals("mock-abc12345", result.getOpenid());
        assertEquals("mock-union-abc12345", result.getUnionid());
        assertEquals("mock-session-abc12345xyz999000", result.getSessionKey());
    }

    @Test
    @DisplayName("同 code 多次调用结果稳定")
    void code2Session_sameCodeMultipleCalls_returnsSameOpenid() {
        WxJscode2SessionResult r1 = adapter.code2Session("test-code-stable");
        WxJscode2SessionResult r2 = adapter.code2Session("test-code-stable");
        assertEquals(r1.getOpenid(), r2.getOpenid());
        assertEquals(r1.getUnionid(), r2.getUnionid());
        assertEquals(r1.getSessionKey(), r2.getSessionKey());
    }

    @Test
    @DisplayName("code 短于 8 位 → openid 用全 code 派生不越界")
    void code2Session_shortCode_usesFullCodeAsPrefix() {
        WxJscode2SessionResult result = adapter.code2Session("ab");
        assertEquals("mock-ab", result.getOpenid());
        assertEquals("mock-union-ab", result.getUnionid());
    }

    @Test
    @DisplayName("code 为空 → ServiceException")
    void code2Session_emptyCode_throws() {
        assertThrows(ServiceException.class, () -> adapter.code2Session(""));
        assertThrows(ServiceException.class, () -> adapter.code2Session(null));
    }

    @Test
    @DisplayName("channel() 返回 'mock'")
    void channel_returnsMock() {
        assertEquals("mock", adapter.channel());
    }
}
