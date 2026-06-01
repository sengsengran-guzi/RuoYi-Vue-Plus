package org.dromara.gz.common.aspect;

import org.dromara.gz.common.mapper.MpStaffSysUserMapper;
import org.dromara.gz.common.service.IMpStaffPermissionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link SysUserStaffKickoutAspect} 单测（GZ-SYS-007 AC9 安全红线分支）。
 *
 * <p>切面 advice 是普通方法，可直接 mock 依赖后单独调用验证逻辑分支（AOP 织入生效性由
 * Spring CGLIB 代理 + execution 切点保证，live 验证，见 reports）。</p>
 *
 * <p>覆盖：</p>
 * <ul>
 *   <li>停用(status='1') 且有更新行 → kickoutByStaffUserId 被调</li>
 *   <li>启用(status='0') → 不踢</li>
 *   <li>停用但更新 0 行 → 不踢</li>
 *   <li>userId=null → 不踢</li>
 *   <li>删除 → 逐个 kickout + clearStaffBindingByStaffUserId</li>
 *   <li>删除空数组 → 无操作</li>
 * </ul>
 *
 * @author kevin-coder (sensenran-guzi · GZ-SYS-007 AC9)
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class SysUserStaffKickoutAspectTest {

    @Mock
    private IMpStaffPermissionService mpStaffPermissionService;
    @Mock
    private MpStaffSysUserMapper staffSysUserMapper;

    @InjectMocks
    private SysUserStaffKickoutAspect aspect;

    @Test
    @DisplayName("停用 sys_user(status='1') 且有更新行 → 自动踢出对应 mp 会话")
    void afterUpdateUserStatus_disabled_kicks() {
        when(mpStaffPermissionService.kickoutByStaffUserId(101L)).thenReturn(1);

        aspect.afterUpdateUserStatus(101L, "1", 1);

        verify(mpStaffPermissionService).kickoutByStaffUserId(101L);
    }

    @Test
    @DisplayName("启用 sys_user(status='0') → 不踢")
    void afterUpdateUserStatus_enabled_noKick() {
        aspect.afterUpdateUserStatus(101L, "0", 1);

        verify(mpStaffPermissionService, never()).kickoutByStaffUserId(any());
    }

    @Test
    @DisplayName("停用但更新 0 行（无变更）→ 不踢")
    void afterUpdateUserStatus_disabledButNoRow_noKick() {
        aspect.afterUpdateUserStatus(101L, "1", 0);

        verify(mpStaffPermissionService, never()).kickoutByStaffUserId(any());
    }

    @Test
    @DisplayName("userId=null → 不踢")
    void afterUpdateUserStatus_nullUserId_noKick() {
        aspect.afterUpdateUserStatus(null, "1", 1);

        verify(mpStaffPermissionService, never()).kickoutByStaffUserId(any());
    }

    @Test
    @DisplayName("踢人抛异常 → 吞掉不打断 ruoyi 停用主流程")
    void afterUpdateUserStatus_kickThrows_swallowed() {
        when(mpStaffPermissionService.kickoutByStaffUserId(101L)).thenThrow(new RuntimeException("redis down"));

        // 不应抛出
        aspect.afterUpdateUserStatus(101L, "1", 1);

        verify(mpStaffPermissionService).kickoutByStaffUserId(101L);
    }

    @Test
    @DisplayName("删除 sys_user → 逐个 kickout + 清 gz_user 悬挂绑定")
    void afterDeleteUserByIds_kicksAndClears() {
        when(mpStaffPermissionService.kickoutByStaffUserId(any())).thenReturn(1);
        when(staffSysUserMapper.clearStaffBindingByStaffUserId(any())).thenReturn(1);

        aspect.afterDeleteUserByIds(null, new Long[]{101L, 102L});

        verify(mpStaffPermissionService).kickoutByStaffUserId(101L);
        verify(mpStaffPermissionService).kickoutByStaffUserId(102L);
        verify(staffSysUserMapper).clearStaffBindingByStaffUserId(101L);
        verify(staffSysUserMapper).clearStaffBindingByStaffUserId(102L);
        verify(mpStaffPermissionService, times(2)).kickoutByStaffUserId(any());
    }

    @Test
    @DisplayName("删除空数组 → 无操作")
    void afterDeleteUserByIds_empty_noOp() {
        aspect.afterDeleteUserByIds(null, new Long[]{});

        verify(mpStaffPermissionService, never()).kickoutByStaffUserId(any());
        verify(staffSysUserMapper, never()).clearStaffBindingByStaffUserId(any());
    }
}
