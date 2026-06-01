package org.dromara.gz.common.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.service.PermissionService;
import org.dromara.gz.common.domain.dto.MpStaffPermission;
import org.dromara.gz.common.domain.dto.StaffSysUserCheck;
import org.dromara.gz.common.domain.entity.GzUser;
import org.dromara.gz.common.mapper.GzUserMapper;
import org.dromara.gz.common.mapper.MpStaffSysUserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.MockedStatic;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * {@link MpStaffPermissionServiceImpl} 单测（ADR-0004 / GZ-SYS-007 底座核心分支）。
 *
 * <p>覆盖：</p>
 * <ul>
 *   <li>绑定店员（同租户 + 正常 + 未软删）→ 加载 RBAC 权限（staff=true，perms 来自 PermissionService）</li>
 *   <li>纯顾客（staff_user_id=null）→ 空载荷（staff=false）</li>
 *   <li>降级分支：sys_user 不存在 / 已软删 / 已停用 / 跨租户 → 均降级纯顾客（不抛错）</li>
 *   <li>kickoutByStaffUserId → 按 staff_user_id 反查 gz_user 并逐个 StpUtil.logout</li>
 *   <li>unbindStaffAndKickout → 置 NULL + 踢；本就非店员 → false 无操作</li>
 * </ul>
 *
 * @author kevin-coder (sensenran-guzi · GZ-SYS-007)
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class MpStaffPermissionServiceImplTest {

    @Mock
    private MpStaffSysUserMapper staffSysUserMapper;
    @Mock
    private PermissionService permissionService;
    @Mock
    private GzUserMapper gzUserMapper;

    @InjectMocks
    private MpStaffPermissionServiceImpl service;

    private GzUser staffGzUser;

    @BeforeEach
    void setUp() {
        staffGzUser = GzUser.builder()
            .id(1L)
            .openid("openid-staff")
            .staffUserId(101L)
            .build();
        staffGzUser.setTenantId("1001");
    }

    private StaffSysUserCheck check(String tenantId, String status, String delFlag) {
        StaffSysUserCheck c = new StaffSysUserCheck();
        c.setUserId(101L);
        c.setUserName("gz_staff_chengdu");
        c.setNickName("成都门店运营");
        c.setTenantId(tenantId);
        c.setStatus(status);
        c.setDelFlag(delFlag);
        return c;
    }

    @Test
    @DisplayName("绑定店员 + 同租户 + 正常 + 未软删 → 加载 RBAC 权限（staff=true）")
    void resolve_validStaff_loadsRbac() {
        when(staffSysUserMapper.selectCheckById(101L)).thenReturn(check("1001", "0", "0"));
        when(permissionService.getRolePermission(101L)).thenReturn(Set.of("staff"));
        when(permissionService.getMenuPermission(101L)).thenReturn(Set.of("gz:bean:booking:verify"));

        MpStaffPermission r = service.resolve(staffGzUser);

        assertTrue(r.isStaff());
        assertEquals(101L, r.getStaffUserId());
        assertEquals("成都门店运营", r.getStaffName());
        assertTrue(r.getMenuPermission().contains("gz:bean:booking:verify"));
        assertTrue(r.getRolePermission().contains("staff"));
    }

    @Test
    @DisplayName("纯顾客（staff_user_id=null）→ 空载荷（staff=false），不查 sys_user")
    void resolve_customer_returnsEmpty() {
        GzUser customer = GzUser.builder().id(2L).openid("openid-cust").staffUserId(null).build();
        customer.setTenantId("1001");

        MpStaffPermission r = service.resolve(customer);

        assertFalse(r.isStaff());
        assertTrue(r.getMenuPermission().isEmpty());
        verify(staffSysUserMapper, never()).selectCheckById(any());
        verify(permissionService, never()).getMenuPermission(any());
    }

    @Test
    @DisplayName("绑定 sys_user 不存在 → 降级纯顾客（不抛错，不加载权限）")
    void resolve_sysUserNotFound_degradesToCustomer() {
        when(staffSysUserMapper.selectCheckById(101L)).thenReturn(null);

        MpStaffPermission r = service.resolve(staffGzUser);

        assertFalse(r.isStaff());
        verify(permissionService, never()).getMenuPermission(any());
    }

    @Test
    @DisplayName("绑定 sys_user 已软删 → 降级纯顾客")
    void resolve_sysUserDeleted_degrades() {
        when(staffSysUserMapper.selectCheckById(101L)).thenReturn(check("1001", "0", "1"));

        MpStaffPermission r = service.resolve(staffGzUser);

        assertFalse(r.isStaff());
        verify(permissionService, never()).getMenuPermission(any());
    }

    @Test
    @DisplayName("绑定 sys_user 已停用(status=1) → 降级纯顾客（安全红线：禁用即时失效）")
    void resolve_sysUserDisabled_degrades() {
        when(staffSysUserMapper.selectCheckById(101L)).thenReturn(check("1001", "1", "0"));

        MpStaffPermission r = service.resolve(staffGzUser);

        assertFalse(r.isStaff());
        verify(permissionService, never()).getMenuPermission(any());
    }

    @Test
    @DisplayName("绑定 sys_user 跨租户 → 降级纯顾客（安全红线：租户一致）")
    void resolve_crossTenant_degrades() {
        when(staffSysUserMapper.selectCheckById(101L)).thenReturn(check("000000", "0", "0"));

        MpStaffPermission r = service.resolve(staffGzUser);

        assertFalse(r.isStaff());
        verify(permissionService, never()).getMenuPermission(any());
    }

    @Test
    @DisplayName("resolveByGzUserId: gz_user 不存在 → 纯顾客")
    void resolveByGzUserId_notFound_customer() {
        when(gzUserMapper.selectById(99L)).thenReturn(null);

        MpStaffPermission r = service.resolveByGzUserId(99L);

        assertFalse(r.isStaff());
    }

    @Test
    @DisplayName("kickoutByStaffUserId → 反查绑定 gz_user 并逐个 StpUtil.logout")
    void kickout_logsOutAllBound() {
        when(staffSysUserMapper.selectGzUserIdsByStaffUserId(101L)).thenReturn(List.of(1L, 5L));

        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            int kicked = service.kickoutByStaffUserId(101L);

            assertEquals(2, kicked);
            stp.verify(() -> StpUtil.logout("app_user:1"));
            stp.verify(() -> StpUtil.logout("app_user:5"));
        }
    }

    @Test
    @DisplayName("kickoutByStaffUserId: 无人绑定 → 0，不调 logout")
    void kickout_noBound_returnsZero() {
        when(staffSysUserMapper.selectGzUserIdsByStaffUserId(101L)).thenReturn(List.of());

        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            int kicked = service.kickoutByStaffUserId(101L);

            assertEquals(0, kicked);
            stp.verifyNoInteractions();
        }
    }

    @Test
    @DisplayName("unbindStaffAndKickout: 原为店员 → 置 NULL + 踢出 + 返 true")
    void unbind_staff_unbindsAndKicks() {
        when(gzUserMapper.selectById(1L)).thenReturn(staffGzUser);
        when(staffSysUserMapper.unbindStaffById(1L)).thenReturn(1);

        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            boolean result = service.unbindStaffAndKickout(1L);

            assertTrue(result);
            verify(staffSysUserMapper).unbindStaffById(1L);
            stp.verify(() -> StpUtil.logout("app_user:1"));
        }
    }

    @Test
    @DisplayName("unbindStaffAndKickout: 本就非店员 → false，无 UPDATE / 无踢")
    void unbind_notStaff_noOp() {
        GzUser customer = GzUser.builder().id(2L).staffUserId(null).build();
        when(gzUserMapper.selectById(2L)).thenReturn(customer);

        boolean result = service.unbindStaffAndKickout(2L);

        assertFalse(result);
        verify(staffSysUserMapper, never()).unbindStaffById(any());
    }

    // ── AC10: owner 自助绑定 ───────────────────────────────────────────────

    @Test
    @DisplayName("bindStaff: 校验通过（同租户+正常+未删）→ UPDATE 绑定 + 返 true")
    void bindStaff_valid_binds() {
        GzUser customer = GzUser.builder().id(2L).staffUserId(null).build();
        customer.setTenantId("1001");
        when(gzUserMapper.selectById(2L)).thenReturn(customer);
        when(staffSysUserMapper.selectCheckById(101L)).thenReturn(check("1001", "0", "0"));
        when(staffSysUserMapper.bindStaffById(2L, 101L)).thenReturn(1);

        boolean result = service.bindStaff(2L, 101L);

        assertTrue(result);
        verify(staffSysUserMapper).bindStaffById(2L, 101L);
    }

    @Test
    @DisplayName("bindStaff: gz_user 不存在 → ServiceException")
    void bindStaff_gzUserNotFound_throws() {
        when(gzUserMapper.selectById(99L)).thenReturn(null);

        assertThrows(ServiceException.class, () -> service.bindStaff(99L, 101L));
        verify(staffSysUserMapper, never()).bindStaffById(any(), any());
    }

    @Test
    @DisplayName("bindStaff: 目标 sys_user 不存在 → ServiceException")
    void bindStaff_sysUserNotFound_throws() {
        GzUser customer = GzUser.builder().id(2L).build();
        customer.setTenantId("1001");
        when(gzUserMapper.selectById(2L)).thenReturn(customer);
        when(staffSysUserMapper.selectCheckById(101L)).thenReturn(null);

        assertThrows(ServiceException.class, () -> service.bindStaff(2L, 101L));
        verify(staffSysUserMapper, never()).bindStaffById(any(), any());
    }

    @Test
    @DisplayName("bindStaff: 目标 sys_user 已停用 → ServiceException")
    void bindStaff_sysUserDisabled_throws() {
        GzUser customer = GzUser.builder().id(2L).build();
        customer.setTenantId("1001");
        when(gzUserMapper.selectById(2L)).thenReturn(customer);
        when(staffSysUserMapper.selectCheckById(101L)).thenReturn(check("1001", "1", "0"));

        assertThrows(ServiceException.class, () -> service.bindStaff(2L, 101L));
        verify(staffSysUserMapper, never()).bindStaffById(any(), any());
    }

    @Test
    @DisplayName("bindStaff: 跨租户 → ServiceException（安全红线：租户一致）")
    void bindStaff_crossTenant_throws() {
        GzUser customer = GzUser.builder().id(2L).build();
        customer.setTenantId("1001");
        when(gzUserMapper.selectById(2L)).thenReturn(customer);
        when(staffSysUserMapper.selectCheckById(101L)).thenReturn(check("000000", "0", "0"));

        assertThrows(ServiceException.class, () -> service.bindStaff(2L, 101L));
        verify(staffSysUserMapper, never()).bindStaffById(any(), any());
    }

    @Test
    @DisplayName("bindStaff: 改绑（原绑别的店员）→ 先踢旧会话再 UPDATE")
    void bindStaff_rebind_kicksOldSession() {
        GzUser bound = GzUser.builder().id(2L).staffUserId(999L).build();
        bound.setTenantId("1001");
        when(gzUserMapper.selectById(2L)).thenReturn(bound);
        when(staffSysUserMapper.selectCheckById(101L)).thenReturn(check("1001", "0", "0"));
        when(staffSysUserMapper.bindStaffById(2L, 101L)).thenReturn(1);

        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            boolean result = service.bindStaff(2L, 101L);

            assertTrue(result);
            stp.verify(() -> StpUtil.logout("app_user:2"));
            verify(staffSysUserMapper).bindStaffById(2L, 101L);
        }
    }

    @Test
    @DisplayName("bindStaff: gzUserId/staffUserId 为 null → ServiceException")
    void bindStaff_nullArgs_throws() {
        assertThrows(ServiceException.class, () -> service.bindStaff(null, 101L));
        assertThrows(ServiceException.class, () -> service.bindStaff(2L, null));
    }
}
