package org.dromara.gz.common.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.gz.common.domain.dto.WxLoginRequest;
import org.dromara.gz.common.domain.entity.GzUser;
import org.dromara.gz.common.domain.vo.WxLoginVO;
import org.dromara.gz.common.service.IGzUserService;
import org.dromara.gz.common.wechat.SessionKeyStore;
import org.dromara.gz.common.wechat.WxJscode2SessionResult;
import org.dromara.gz.common.wechat.WxLoginAdapter;
import org.dromara.gz.common.wechat.WxMiniappProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * {@link WxLoginServiceImpl} 单测（GZ-SYS-003 改造版）。
 *
 * <p>GZ-SYS-003 起 DAO 从 GzUserRepository 切换为 {@link IGzUserService.upsertByOpenid}；
 * 本测试 mock IGzUserService 验证 service 编排逻辑（不验 DB 落库 — 那是 GzUserServiceImplTest 的职责）。</p>
 *
 * <p>覆盖路径：</p>
 * <ul>
 *   <li>happy path 1：mock 通道 + 新用户 → IGzUserService.upsertByOpenid 调用 + 颁 token</li>
 *   <li>happy path 2：mock 通道 + 已存在用户 → UPSERT 返同样实体 + 复用 id</li>
 *   <li>异常 1：real 通道 jscode2session 失败 → ServiceException 透传</li>
 *   <li>异常 2：已禁用用户 → ServiceException "账号已禁用"</li>
 * </ul>
 *
 * @author kevin-coder (sensenran-guzi)
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class WxLoginServiceImplTest {

    @Mock
    private WxLoginAdapter wxLoginAdapter;

    @Mock
    private IGzUserService gzUserService;

    @Mock
    private SessionKeyStore sessionKeyStore;

    private WxMiniappProperties properties;

    private WxLoginServiceImpl wxLoginService;

    @BeforeEach
    void setUp() {
        properties = new WxMiniappProperties();
        wxLoginService = new WxLoginServiceImpl(wxLoginAdapter, properties, gzUserService, sessionKeyStore);
    }

    @Test
    @DisplayName("mock 通道 + 新用户 → upsertByOpenid 调用 + 颁 token")
    void wxLogin_mockChannel_newUser_upsertsAndIssuesToken() {
        WxJscode2SessionResult mockSession = WxJscode2SessionResult.builder()
            .openid("mock-abc12345")
            .unionid("mock-union-abc12345")
            .sessionKey("mock-session-test-code")
            .build();
        when(wxLoginAdapter.code2Session("test-code-12345")).thenReturn(mockSession);
        when(wxLoginAdapter.channel()).thenReturn("mock");

        // mock IGzUserService.upsertByOpenid 返回 fresh user
        GzUser fresh = GzUser.builder()
            .id(1001L)
            .userNo("U20260601000001")
            .openid("mock-abc12345")
            .unionid("mock-union-abc12345")
            .nickname("测试用户")
            .avatarUrl("https://example.com/avatar.png")
            .status("authorized")
            .isDisabled(0)
            .build();
        fresh.setTenantId("1001");
        when(gzUserService.upsertByOpenid(eq(mockSession), eq("测试用户"), eq("https://example.com/avatar.png")))
            .thenReturn(fresh);

        WxLoginRequest req = new WxLoginRequest();
        req.setCode("test-code-12345");
        req.setNickName("测试用户");
        req.setAvatarUrl("https://example.com/avatar.png");

        try (MockedStatic<StpUtil> stpMock = Mockito.mockStatic(StpUtil.class, Mockito.RETURNS_DEEP_STUBS)) {
            stpMock.when(StpUtil::getTokenValue).thenReturn("test-sa-token-value");

            WxLoginVO vo = wxLoginService.wxLogin(req);

            assertNotNull(vo);
            assertEquals("test-sa-token-value", vo.getToken());
            assertEquals(properties.getTokenTtlSeconds(), vo.getExpiresIn());
            assertEquals(1001L, vo.getUserId());
            assertEquals("mock-abc12345", vo.getOpenid());
            assertEquals("测试用户", vo.getNickName());
            assertEquals("https://example.com/avatar.png", vo.getAvatarUrl());

            verify(gzUserService).upsertByOpenid(eq(mockSession), eq("测试用户"), eq("https://example.com/avatar.png"));
            verify(sessionKeyStore).put("mock-abc12345", "mock-session-test-code");
        }
    }

    @Test
    @DisplayName("mock 通道 + 已存在用户 → 复用 id + 颁新 token")
    void wxLogin_mockChannel_existingUser_reusesId() {
        WxJscode2SessionResult mockSession = WxJscode2SessionResult.builder()
            .openid("mock-existing")
            .unionid("mock-union-fresh")
            .sessionKey("mock-session-existing")
            .build();
        when(wxLoginAdapter.code2Session("existing-code")).thenReturn(mockSession);
        when(wxLoginAdapter.channel()).thenReturn("mock");

        GzUser existing = GzUser.builder()
            .id(2002L)
            .openid("mock-existing")
            .unionid("mock-union-fresh")
            .nickname("新昵称")
            .avatarUrl("https://new.example.com/avatar.png")
            .status("authorized")
            .isDisabled(0)
            .build();
        existing.setTenantId("1001");
        when(gzUserService.upsertByOpenid(eq(mockSession), anyString(), anyString())).thenReturn(existing);

        WxLoginRequest req = new WxLoginRequest();
        req.setCode("existing-code");
        req.setNickName("新昵称");
        req.setAvatarUrl("https://new.example.com/avatar.png");

        try (MockedStatic<StpUtil> stpMock = Mockito.mockStatic(StpUtil.class, Mockito.RETURNS_DEEP_STUBS)) {
            stpMock.when(StpUtil::getTokenValue).thenReturn("token-existing");

            WxLoginVO vo = wxLoginService.wxLogin(req);

            assertEquals(2002L, vo.getUserId());
            assertEquals("新昵称", vo.getNickName());
            verify(sessionKeyStore).put("mock-existing", "mock-session-existing");
        }
    }

    @Test
    @DisplayName("real 通道 jscode2session 失败 → ServiceException 透传")
    void wxLogin_realChannel_jscode2sessionFails_throws() {
        when(wxLoginAdapter.code2Session("bad-code"))
            .thenThrow(new ServiceException("微信登录失败: invalid code"));

        WxLoginRequest req = new WxLoginRequest();
        req.setCode("bad-code");

        ServiceException ex = assertThrows(ServiceException.class, () -> wxLoginService.wxLogin(req));
        assertTrue(ex.getMessage().contains("微信登录失败"));

        verify(gzUserService, never()).upsertByOpenid(any(), any(), any());
        verify(sessionKeyStore, never()).put(anyString(), anyString());
    }

    @Test
    @DisplayName("已禁用用户登录 → ServiceException 不颁 token")
    void wxLogin_disabledUser_throwsAndDoesNotIssueToken() {
        WxJscode2SessionResult mockSession = WxJscode2SessionResult.builder()
            .openid("mock-disabled")
            .sessionKey("mock-session-disabled")
            .build();
        when(wxLoginAdapter.code2Session("disabled-code")).thenReturn(mockSession);
        when(wxLoginAdapter.channel()).thenReturn("mock");

        GzUser disabledUser = GzUser.builder()
            .id(3003L)
            .openid("mock-disabled")
            .nickname("被禁用的用户")
            .isDisabled(1)
            .status("authorized")
            .build();
        when(gzUserService.upsertByOpenid(eq(mockSession), any(), any())).thenReturn(disabledUser);

        WxLoginRequest req = new WxLoginRequest();
        req.setCode("disabled-code");

        ServiceException ex = assertThrows(ServiceException.class, () -> wxLoginService.wxLogin(req));
        assertTrue(ex.getMessage().contains("账号已禁用"));

        verify(sessionKeyStore, never()).put(anyString(), anyString());
    }
}
