package org.dromara.gz.common.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.R;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.dromara.gz.common.service.IMpStaffPermissionService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * mp 店员绑定管理（admin 端，ADR-0004 / GZ-SYS-007）。
 *
 * <p>路径 {@code /system/gz/staff}。V1.0 仅提供 owner 解绑 + 禁用即时踢人（安全红线 AC5 的
 * 可验证手段）；自助绑定 UI（owner 给微信号分店员身份）属 V1.1，本期绑定走 seed/手工 SQL（ADR 范围切分）。</p>
 *
 * <p>权限：{@code @SaCheckRole("owner")} —— 解绑/踢人属敏感操作，仅主理人可执行。
 * superadmin（租户超管）天然带 owner 之上权限，由其自行在 admin 用户管理操作 sys_user 停用。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-SYS-007)
 */
@Slf4j
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/system/gz/staff")
public class GzStaffBindingController {

    private final IMpStaffPermissionService mpStaffPermissionService;

    /**
     * 解绑某 mp 用户的店员身份并即时踢出其会话（ADR-0004 安全红线 AC5）。
     *
     * <pre>
     * POST /system/gz/staff/{gzUserId}/unbind
     * 200 OK { "code": 200, "data": true }   // true=原为店员已解绑+踢出；false=本就非店员
     * </pre>
     *
     * @param gzUserId 要解绑的 mp 用户 gz_user.id
     */
    @SaCheckRole("owner")
    @Log(title = "解绑 mp 店员身份", businessType = BusinessType.UPDATE)
    @PostMapping("/{gzUserId}/unbind")
    public R<Boolean> unbind(@PathVariable @NotNull Long gzUserId) {
        log.info("[gz-staff-admin] unbind gzUserId={}", gzUserId);
        return R.ok(mpStaffPermissionService.unbindStaffAndKickout(gzUserId));
    }

    /**
     * 禁用某店员 sys_user 后，手动即时踢出所有绑定它的 mp 会话（ADR-0004 安全红线 AC5）。
     *
     * <p>使用时序：owner 在「系统管理→用户管理」停用某店员 sys_user 后，调本端点把仍持旧 token
     * 的该店员 mp 会话立即清掉（停用后即便不踢，下次登录 resolve 也已校验 status 降级为纯顾客，
     * 本端点解决"已登录 token 即时失效"）。</p>
     *
     * <pre>
     * POST /system/gz/staff/kickout/{staffUserId}
     * 200 OK { "code": 200, "data": 1 }   // 被踢的 mp 会话数
     * </pre>
     *
     * @param staffUserId 被停用的 sys_user.user_id
     */
    @SaCheckRole("owner")
    @Log(title = "踢出店员 mp 会话", businessType = BusinessType.UPDATE)
    @PostMapping("/kickout/{staffUserId}")
    public R<Integer> kickout(@PathVariable @NotNull Long staffUserId) {
        log.info("[gz-staff-admin] kickout staffUserId={}", staffUserId);
        return R.ok(mpStaffPermissionService.kickoutByStaffUserId(staffUserId));
    }
}
