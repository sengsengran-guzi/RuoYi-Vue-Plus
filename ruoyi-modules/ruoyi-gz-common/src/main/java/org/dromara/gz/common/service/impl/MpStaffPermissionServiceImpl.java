package org.dromara.gz.common.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.dromara.common.core.constant.SystemConstants;
import org.dromara.common.core.enums.UserType;
import org.dromara.common.core.service.PermissionService;
import org.dromara.gz.common.domain.dto.MpStaffPermission;
import org.dromara.gz.common.domain.dto.StaffSysUserCheck;
import org.dromara.gz.common.domain.entity.GzUser;
import org.dromara.gz.common.mapper.GzUserMapper;
import org.dromara.gz.common.mapper.MpStaffSysUserMapper;
import org.dromara.gz.common.service.IMpStaffPermissionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

/**
 * mp 管理端权限底座实现（ADR-0004 / GZ-SYS-007）。
 *
 * <p><b>权限装载链路验证结论</b>（实现前小样验证，写入 reports）：</p>
 * <ul>
 *   <li>{@code SaPermissionImpl#getPermissionList}（ruoyi-common-satoken）第 43 行对当前 token 的
 *       LoginUser <b>不区分 userType</b> 直接返回 {@code loginUser.getMenuPermission()}；APP_USER 的
 *       那个 if 分支是空的（"自行根据业务编写"）。→ 只要登录时把 menuPermission/rolePermission 装进
 *       APP_USER 的 LoginUser，{@code @SaCheckPermission} 就在 mp 端点直接生效，<b>无需改 ruoyi 源码</b>。</li>
 *   <li>权限真源 = ruoyi {@code PermissionService}（ruoyi-common-core SPI，运行时注入
 *       {@code SysPermissionServiceImpl}）。gz-common 已依赖 ruoyi-common-core，零新增模块依赖，
 *       与 admin {@code SysLoginService#buildLoginUser} 同一加载入口。</li>
 * </ul>
 *
 * @author kevin-coder (sensenran-guzi · GZ-SYS-007)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MpStaffPermissionServiceImpl implements IMpStaffPermissionService {

    private final MpStaffSysUserMapper staffSysUserMapper;
    private final PermissionService permissionService;
    private final GzUserMapper gzUserMapper;

    @Override
    public MpStaffPermission resolveByGzUserId(Long gzUserId) {
        if (gzUserId == null) {
            return MpStaffPermission.customer();
        }
        GzUser gzUser = gzUserMapper.selectById(gzUserId);
        if (gzUser == null) {
            log.warn("[mp-staff] resolveByGzUserId gz_user id={} 不存在 → 纯顾客", gzUserId);
            return MpStaffPermission.customer();
        }
        return resolve(gzUser);
    }

    @Override
    public MpStaffPermission resolve(GzUser gzUser) {
        if (gzUser == null || gzUser.getStaffUserId() == null) {
            // 纯顾客 — 不绑定任何 sys_user
            return MpStaffPermission.customer();
        }

        Long staffUserId = gzUser.getStaffUserId();
        StaffSysUserCheck check = staffSysUserMapper.selectCheckById(staffUserId);

        // 校验：存在 + 未软删 + status 正常 + 同租户（ADR 安全红线"租户一致"）
        if (check == null) {
            log.warn("[mp-staff] gz_user id={} 绑定的 sys_user={} 不存在 → 降级纯顾客",
                gzUser.getId(), staffUserId);
            return MpStaffPermission.customer();
        }
        if (!SystemConstants.NORMAL.equals(check.getDelFlag())) {
            log.warn("[mp-staff] gz_user id={} 绑定的 sys_user={} 已软删 → 降级纯顾客",
                gzUser.getId(), staffUserId);
            return MpStaffPermission.customer();
        }
        if (!SystemConstants.NORMAL.equals(check.getStatus())) {
            log.warn("[mp-staff] gz_user id={} 绑定的 sys_user={} 已停用(status={}) → 降级纯顾客",
                gzUser.getId(), staffUserId, check.getStatus());
            return MpStaffPermission.customer();
        }
        if (!StringUtils.equals(gzUser.getTenantId(), check.getTenantId())) {
            log.warn("[mp-staff] gz_user id={}(tenant={}) 与绑定 sys_user={}(tenant={}) 跨租户 → 降级纯顾客",
                gzUser.getId(), gzUser.getTenantId(), staffUserId, check.getTenantId());
            return MpStaffPermission.customer();
        }

        // 校验通过 → 复用 ruoyi RBAC 装载（与 admin 同一真源，不手写权限串）
        Set<String> roles = permissionService.getRolePermission(staffUserId);
        Set<String> perms = permissionService.getMenuPermission(staffUserId);
        log.info("[mp-staff] gz_user id={} 绑定店员 sys_user={}({}) 加载 roles={} perms={}",
            gzUser.getId(), staffUserId, check.getUserName(), roles, perms.size());

        return MpStaffPermission.builder()
            .staff(true)
            .staffUserId(staffUserId)
            .staffName(check.getNickName())
            .rolePermission(roles)
            .menuPermission(perms)
            .build();
    }

    @Override
    public int kickoutByStaffUserId(Long staffUserId) {
        if (staffUserId == null) {
            return 0;
        }
        List<Long> gzUserIds = staffSysUserMapper.selectGzUserIdsByStaffUserId(staffUserId);
        int kicked = 0;
        for (Long gzUserId : gzUserIds) {
            try {
                // app_user loginId = "app_user:{gzUserId}"（LoginUser.getLoginId() 口径）
                StpUtil.logout(UserType.APP_USER.getUserType() + ":" + gzUserId);
                kicked++;
            } catch (Exception e) {
                log.warn("[mp-staff] kickout gz_user={} 失败: {}", gzUserId, e.getMessage());
            }
        }
        log.info("[mp-staff] 解绑/禁用 sys_user={} → 踢出 {} 个 mp 会话", staffUserId, kicked);
        return kicked;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean unbindStaffAndKickout(Long gzUserId) {
        if (gzUserId == null) {
            return false;
        }
        GzUser gzUser = gzUserMapper.selectById(gzUserId);
        if (gzUser == null || gzUser.getStaffUserId() == null) {
            // 本就非店员，无操作
            return false;
        }
        Long staffUserId = gzUser.getStaffUserId();

        // ① UPDATE gz_user.staff_user_id = NULL（显式 SQL，不依赖 lambda cache）
        staffSysUserMapper.unbindStaffById(gzUserId);

        // ② 即时踢出该 mp 用户全部 token（不靠下次登录）
        try {
            StpUtil.logout(UserType.APP_USER.getUserType() + ":" + gzUserId);
        } catch (Exception e) {
            log.warn("[mp-staff] unbind kickout gz_user={} 失败: {}", gzUserId, e.getMessage());
        }

        log.info("[mp-staff] 解绑 gz_user={}（原绑 sys_user={}）+ 即时踢出会话", gzUserId, staffUserId);
        return true;
    }
}
