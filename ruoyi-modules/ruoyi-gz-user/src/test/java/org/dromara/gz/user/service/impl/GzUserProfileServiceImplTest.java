package org.dromara.gz.user.service.impl;

import org.dromara.common.core.exception.ServiceException;
import org.dromara.gz.common.domain.entity.GzUser;
import org.dromara.gz.common.domain.vo.GzUserVO;
import org.dromara.gz.common.mapper.GzUserMapper;
import org.dromara.gz.common.service.IGzUserService;
import org.dromara.gz.user.domain.bo.UserProfileUpdateBo;
import org.dromara.gz.user.domain.entity.GzUserAuditLog;
import org.dromara.gz.user.mapper.GzUserAuditLogMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * {@link GzUserProfileServiceImpl} 单测（GZ-USER-002 AC 6）。
 *
 * <p>覆盖：</p>
 * <ul>
 *   <li>happy path：改昵称 → UPDATE gz_user + 写 1 条 update_nickname 审计</li>
 *   <li>多字段变化：昵称 + 头像 + 性别 → 写 3 条审计</li>
 *   <li>无变化：传相同值 → 不 UPDATE 不写审计，返回当前 VO</li>
 *   <li>用户不存在 → ServiceException</li>
 *   <li>null 字段不更新（仅传昵称，头像 / 性别 null → 不动）</li>
 * </ul>
 *
 * @author kevin-coder (sensenran-guzi · GZ-USER-002)
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class GzUserProfileServiceImplTest {

    @Mock
    private GzUserMapper gzUserMapper;

    @Mock
    private GzUserAuditLogMapper auditLogMapper;

    @Mock
    private IGzUserService gzUserService;

    private GzUserProfileServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new GzUserProfileServiceImpl(gzUserMapper, auditLogMapper, gzUserService);
        // 读侧 VO 统一走 gzUserService.selectVoById（ADR-0009 头像 URL 重生成），默认返空 VO，
        // 个别用例覆盖返回特定 VO。
        lenient().when(gzUserService.selectVoById(any())).thenReturn(new GzUserVO());
    }

    private GzUser existingUser() {
        GzUser u = GzUser.builder()
            .id(1001L)
            .nickname("旧昵称")
            .avatarUrl("https://old/a.png")
            .avatarImageId(10L)
            .gender(0)
            .wechatId("old_wx")
            .status("authorized")
            .build();
        u.setTenantId("1001");
        return u;
    }

    @Test
    @DisplayName("改昵称 → UPDATE + 写 1 条 update_nickname 审计")
    void updateProfile_nickname_writesOneAuditLog() {
        when(gzUserMapper.selectById(1001L)).thenReturn(existingUser());
        when(gzUserMapper.updateById(any(GzUser.class))).thenReturn(1);
        GzUserVO vo = new GzUserVO();
        vo.setId(1001L);
        vo.setNickname("阿喵");
        // 返回 VO 走 gzUserService.selectVoById（ADR-0009 读侧 avatar URL 重生成）
        when(gzUserService.selectVoById(1001L)).thenReturn(vo);

        UserProfileUpdateBo bo = new UserProfileUpdateBo();
        bo.setNickname("阿喵");

        GzUserVO result = service.updateProfile(1001L, bo, "1.2.3.4");

        assertNotNull(result);
        assertEquals("阿喵", result.getNickname());
        verify(gzUserMapper).updateById(any(GzUser.class));

        ArgumentCaptor<GzUserAuditLog> captor = ArgumentCaptor.forClass(GzUserAuditLog.class);
        verify(auditLogMapper, times(1)).insert(captor.capture());
        GzUserAuditLog logged = captor.getValue();
        assertEquals("update_nickname", logged.getActionType());
        assertEquals("旧昵称", logged.getBeforeValue());
        assertEquals("阿喵", logged.getAfterValue());
        assertEquals(1001L, logged.getUserId());
        assertEquals("1.2.3.4", logged.getIp());
    }

    @Test
    @DisplayName("昵称 + 头像(url 兜底) + 性别全改 → 写 3 条审计")
    void updateProfile_allFields_writesThreeAuditLogs() {
        when(gzUserMapper.selectById(1001L)).thenReturn(existingUser());
        when(gzUserMapper.updateById(any(GzUser.class))).thenReturn(1);

        UserProfileUpdateBo bo = new UserProfileUpdateBo();
        bo.setNickname("阿喵");
        bo.setAvatarUrl("https://new/a.png"); // 未传 avatarImageId → 走 url 兜底分支
        bo.setGender(2);

        service.updateProfile(1001L, bo, "1.2.3.4");

        verify(auditLogMapper, times(3)).insert(any(GzUserAuditLog.class));
        verify(gzUserMapper, times(1)).updateById(any(GzUser.class));
    }

    @Test
    @DisplayName("传相同值 → 不 UPDATE 不写审计")
    void updateProfile_noChange_skipsUpdateAndAudit() {
        when(gzUserMapper.selectById(1001L)).thenReturn(existingUser());

        UserProfileUpdateBo bo = new UserProfileUpdateBo();
        bo.setNickname("旧昵称");
        bo.setAvatarUrl("https://old/a.png");
        bo.setGender(0);
        bo.setWechatId("old_wx");

        service.updateProfile(1001L, bo, "1.2.3.4");

        verify(gzUserMapper, never()).updateById(any(GzUser.class));
        verify(auditLogMapper, never()).insert(any(GzUserAuditLog.class));
    }

    @Test
    @DisplayName("仅传昵称（头像 / 性别 / 微信号 null）→ 只动昵称，写 1 条审计")
    void updateProfile_partialNullFields_onlyUpdatesNickname() {
        when(gzUserMapper.selectById(1001L)).thenReturn(existingUser());
        when(gzUserMapper.updateById(any(GzUser.class))).thenReturn(1);

        UserProfileUpdateBo bo = new UserProfileUpdateBo();
        bo.setNickname("阿喵");
        // avatarImageId / avatarUrl / gender / wechatId 不传 → null

        service.updateProfile(1001L, bo, null);

        verify(auditLogMapper, times(1)).insert(any(GzUserAuditLog.class));
    }

    /* ============================================================
     * GZ-USER-005 微信号 + GZ-USER-006 头像 image_id 扩展用例
     * ============================================================ */

    @Test
    @DisplayName("GZ-USER-005：填微信号 → UPDATE wechat_id + 写 1 条 update_wechat_id 审计")
    void updateProfile_wechatId_writesAuditAndPersists() {
        when(gzUserMapper.selectById(1001L)).thenReturn(existingUser());
        when(gzUserMapper.updateById(any(GzUser.class))).thenReturn(1);

        UserProfileUpdateBo bo = new UserProfileUpdateBo();
        bo.setWechatId("new_wx_id");

        service.updateProfile(1001L, bo, "1.2.3.4");

        ArgumentCaptor<GzUser> userCaptor = ArgumentCaptor.forClass(GzUser.class);
        verify(gzUserMapper).updateById(userCaptor.capture());
        assertEquals("new_wx_id", userCaptor.getValue().getWechatId());

        ArgumentCaptor<GzUserAuditLog> logCaptor = ArgumentCaptor.forClass(GzUserAuditLog.class);
        verify(auditLogMapper, times(1)).insert(logCaptor.capture());
        assertEquals("update_wechat_id", logCaptor.getValue().getActionType());
        assertEquals("old_wx", logCaptor.getValue().getBeforeValue());
        assertEquals("new_wx_id", logCaptor.getValue().getAfterValue());
    }

    @Test
    @DisplayName("GZ-USER-005：微信号传空 → 不报错、不更新、不写审计")
    void updateProfile_wechatIdBlank_noChange() {
        when(gzUserMapper.selectById(1001L)).thenReturn(existingUser());

        UserProfileUpdateBo bo = new UserProfileUpdateBo();
        bo.setWechatId("   "); // 空白 → 视为不修改

        // 不抛异常
        assertDoesNotThrow(() -> service.updateProfile(1001L, bo, null));
        verify(gzUserMapper, never()).updateById(any(GzUser.class));
        verify(auditLogMapper, never()).insert(any(GzUserAuditLog.class));
    }

    @Test
    @DisplayName("GZ-USER-006：传 avatarImageId → 落 avatar_image_id（不依赖前端 url）+ 写 update_avatar")
    void updateProfile_avatarImageId_persistsImageIdNotRawUrl() {
        when(gzUserMapper.selectById(1001L)).thenReturn(existingUser());
        when(gzUserMapper.updateById(any(GzUser.class))).thenReturn(1);

        UserProfileUpdateBo bo = new UserProfileUpdateBo();
        bo.setAvatarImageId(99L);
        // 故意同时传一个 url，验证 image_id 优先、不被 url 干扰（头像存 image_id 不存裸 url）
        bo.setAvatarUrl("https://should-be-ignored/x.png");

        service.updateProfile(1001L, bo, "1.2.3.4");

        ArgumentCaptor<GzUser> userCaptor = ArgumentCaptor.forClass(GzUser.class);
        verify(gzUserMapper).updateById(userCaptor.capture());
        GzUser saved = userCaptor.getValue();
        assertEquals(99L, saved.getAvatarImageId());
        // avatar_url 未被前端裸 url 覆盖（仍是旧值；读侧由 selectVoById 重生成签名 URL）
        assertEquals("https://old/a.png", saved.getAvatarUrl());

        ArgumentCaptor<GzUserAuditLog> logCaptor = ArgumentCaptor.forClass(GzUserAuditLog.class);
        verify(auditLogMapper, times(1)).insert(logCaptor.capture());
        assertEquals("update_avatar", logCaptor.getValue().getActionType());
    }

    @Test
    @DisplayName("GZ-USER-006：avatarImageId 与现值相同 → 不更新（持久化幂等）")
    void updateProfile_avatarImageIdSame_noChange() {
        when(gzUserMapper.selectById(1001L)).thenReturn(existingUser());

        UserProfileUpdateBo bo = new UserProfileUpdateBo();
        bo.setAvatarImageId(10L); // existingUser 现值即 10

        service.updateProfile(1001L, bo, null);

        verify(gzUserMapper, never()).updateById(any(GzUser.class));
        verify(auditLogMapper, never()).insert(any(GzUserAuditLog.class));
    }

    @Test
    @DisplayName("用户不存在 → ServiceException")
    void updateProfile_userNotFound_throws() {
        when(gzUserMapper.selectById(9999L)).thenReturn(null);
        UserProfileUpdateBo bo = new UserProfileUpdateBo();
        bo.setNickname("x");

        assertThrows(ServiceException.class, () -> service.updateProfile(9999L, bo, null));
        verify(gzUserMapper, never()).updateById(any(GzUser.class));
    }

    @Test
    @DisplayName("userId null → ServiceException")
    void updateProfile_nullUserId_throws() {
        UserProfileUpdateBo bo = new UserProfileUpdateBo();
        assertThrows(ServiceException.class, () -> service.updateProfile(null, bo, null));
    }
}
