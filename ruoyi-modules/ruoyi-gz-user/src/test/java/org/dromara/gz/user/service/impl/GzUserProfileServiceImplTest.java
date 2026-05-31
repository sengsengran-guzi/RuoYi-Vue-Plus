package org.dromara.gz.user.service.impl;

import org.dromara.common.core.exception.ServiceException;
import org.dromara.gz.common.domain.entity.GzUser;
import org.dromara.gz.common.domain.vo.GzUserVO;
import org.dromara.gz.common.mapper.GzUserMapper;
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

    private GzUserProfileServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new GzUserProfileServiceImpl(gzUserMapper, auditLogMapper);
    }

    private GzUser existingUser() {
        GzUser u = GzUser.builder()
            .id(1001L)
            .nickname("旧昵称")
            .avatarUrl("https://old/a.png")
            .gender(0)
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
        when(gzUserMapper.selectVoById(1001L)).thenReturn(vo);

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
    @DisplayName("昵称 + 头像 + 性别全改 → 写 3 条审计")
    void updateProfile_allFields_writesThreeAuditLogs() {
        when(gzUserMapper.selectById(1001L)).thenReturn(existingUser());
        when(gzUserMapper.updateById(any(GzUser.class))).thenReturn(1);
        when(gzUserMapper.selectVoById(1001L)).thenReturn(new GzUserVO());

        UserProfileUpdateBo bo = new UserProfileUpdateBo();
        bo.setNickname("阿喵");
        bo.setAvatarUrl("https://new/a.png");
        bo.setGender(2);

        service.updateProfile(1001L, bo, "1.2.3.4");

        verify(auditLogMapper, times(3)).insert(any(GzUserAuditLog.class));
        verify(gzUserMapper, times(1)).updateById(any(GzUser.class));
    }

    @Test
    @DisplayName("传相同值 → 不 UPDATE 不写审计")
    void updateProfile_noChange_skipsUpdateAndAudit() {
        when(gzUserMapper.selectById(1001L)).thenReturn(existingUser());
        when(gzUserMapper.selectVoById(1001L)).thenReturn(new GzUserVO());

        UserProfileUpdateBo bo = new UserProfileUpdateBo();
        bo.setNickname("旧昵称");
        bo.setAvatarUrl("https://old/a.png");
        bo.setGender(0);

        service.updateProfile(1001L, bo, "1.2.3.4");

        verify(gzUserMapper, never()).updateById(any(GzUser.class));
        verify(auditLogMapper, never()).insert(any(GzUserAuditLog.class));
    }

    @Test
    @DisplayName("仅传昵称（头像 / 性别 null）→ 只动昵称，写 1 条审计")
    void updateProfile_partialNullFields_onlyUpdatesNickname() {
        when(gzUserMapper.selectById(1001L)).thenReturn(existingUser());
        when(gzUserMapper.updateById(any(GzUser.class))).thenReturn(1);
        when(gzUserMapper.selectVoById(1001L)).thenReturn(new GzUserVO());

        UserProfileUpdateBo bo = new UserProfileUpdateBo();
        bo.setNickname("阿喵");
        // avatarUrl / gender 不传 → null

        service.updateProfile(1001L, bo, null);

        verify(auditLogMapper, times(1)).insert(any(GzUserAuditLog.class));
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
