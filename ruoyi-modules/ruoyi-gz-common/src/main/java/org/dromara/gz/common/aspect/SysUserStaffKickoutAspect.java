package org.dromara.gz.common.aspect;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.Aspect;
import org.dromara.common.core.enums.UserStatus;
import org.dromara.gz.common.mapper.MpStaffSysUserMapper;
import org.dromara.gz.common.service.IMpStaffPermissionService;
import org.springframework.stereotype.Component;

/**
 * 停用 / 删除 ruoyi {@code sys_user} 时自动踢出对应 mp 会话（ADR-0004 安全红线 / GZ-SYS-007 AC9）。
 *
 * <p><b>问题</b>：ruoyi 原生在「系统管理→用户管理」停用 / 删除某店员 sys_user 时，<b>不会</b>踢掉已
 * 加载了该店员权限的 app_user mp token（两者是不同 loginId：{@code app_user:N} vs {@code sys_user:101}）。
 * 若不处理，被停用的店员持旧 token 仍能调 mp 管理端点，直到 token 过期或下次登录才降级——安全漏。</p>
 *
 * <p><b>方案</b>：Spring AOP 切面包住 ruoyi 服务方法（<b>不改 ruoyi 源码</b>，铁律 #1）。用纯
 * {@code execution()} 指目标方法 FQN，<b>不 import 任何 ruoyi-system 类</b>，故 gz-common 无需新增
 * ruoyi-system 模块依赖；方法参数经 {@link JoinPoint#getArgs()} 反射取值，类型 erasure 后用 Long / Long[]。
 * 外部调用（admin {@code SysUserController} → Spring 代理 → 切面）织入生效。</p>
 *
 * <p><b>切点</b>（{@code org.dromara.system.service.impl.SysUserServiceImpl}）：</p>
 * <ul>
 *   <li>{@code updateUserStatus(Long userId, String status)}：仅当 status='1'（停用，{@link UserStatus#DISABLE}）
 *       且返回行数 &gt; 0 → {@code kickoutByStaffUserId(userId)}。启用（'0'）不踢。</li>
 *   <li>{@code deleteUserByIds(Long[] userIds)}：删除成功后逐个 kickout + 把绑定该 user 的
 *       gz_user.staff_user_id 置 NULL（清悬挂引用）。</li>
 * </ul>
 *
 * <p><b>幂等 / 安全</b>：切面在目标方法 {@code @AfterReturning}（成功返回后）执行；本身不抛错打断
 * ruoyi 主流程（踢人失败仅记日志，下次登录 resolve 仍会按 status/del_flag 降级兜底）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-SYS-007 AC9)
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class SysUserStaffKickoutAspect {

    private final IMpStaffPermissionService mpStaffPermissionService;
    private final MpStaffSysUserMapper staffSysUserMapper;

    /**
     * 切 {@code SysUserServiceImpl#updateUserStatus(Long, String)}：停用成功后踢对应 mp 会话。
     *
     * @param userId 第一个参数 sys_user.user_id（JoinPoint 取）
     * @param status 第二个参数 账号状态（'0'=正常 / '1'=停用）
     * @param result 返回值（影响行数）
     */
    @AfterReturning(
        pointcut = "execution(int org.dromara.system.service.impl.SysUserServiceImpl.updateUserStatus(Long, String)) && args(userId, status)",
        returning = "result",
        argNames = "userId,status,result")
    public void afterUpdateUserStatus(Long userId, String status, Object result) {
        // 只在"停用"且确有更新行时踢；启用 / 无变更不踢
        if (userId == null || !UserStatus.DISABLE.getCode().equals(status)) {
            return;
        }
        if (result instanceof Integer rows && rows <= 0) {
            return;
        }
        try {
            int kicked = mpStaffPermissionService.kickoutByStaffUserId(userId);
            if (kicked > 0) {
                log.info("[gz-staff-aop] 停用 sys_user={} → 自动踢出 {} 个 mp 会话", userId, kicked);
            }
        } catch (Exception e) {
            // 不打断 ruoyi 停用主流程；下次登录 resolve 仍按 status 降级兜底
            log.warn("[gz-staff-aop] 停用 sys_user={} 自动踢人失败（不影响停用本身）: {}", userId, e.getMessage());
        }
    }

    /**
     * 切 {@code SysUserServiceImpl#deleteUserByIds(Long[])}：删除成功后逐个踢 + 清 gz_user 悬挂绑定。
     *
     * @param userIds 被删除的 sys_user.user_id 数组（JoinPoint 取）
     * @param jp      连接点（仅用于日志）
     */
    @AfterReturning(
        pointcut = "execution(int org.dromara.system.service.impl.SysUserServiceImpl.deleteUserByIds(Long[])) && args(userIds)",
        argNames = "jp,userIds")
    public void afterDeleteUserByIds(JoinPoint jp, Long[] userIds) {
        if (userIds == null || userIds.length == 0) {
            return;
        }
        for (Long userId : userIds) {
            if (userId == null) {
                continue;
            }
            try {
                // ① 踢出该店员的所有 mp 会话（先踢，再清悬挂）
                int kicked = mpStaffPermissionService.kickoutByStaffUserId(userId);
                // ② 清 gz_user 悬挂绑定（staff_user_id 指向已删 sys_user → 置 NULL）
                int cleared = staffSysUserMapper.clearStaffBindingByStaffUserId(userId);
                if (kicked > 0 || cleared > 0) {
                    log.info("[gz-staff-aop] 删除 sys_user={} → 踢出 {} 个 mp 会话 + 清 {} 条 gz_user 悬挂绑定",
                        userId, kicked, cleared);
                }
            } catch (Exception e) {
                log.warn("[gz-staff-aop] 删除 sys_user={} 后清理 mp 绑定失败（不影响删除本身）: {}",
                    userId, e.getMessage());
            }
        }
    }
}
