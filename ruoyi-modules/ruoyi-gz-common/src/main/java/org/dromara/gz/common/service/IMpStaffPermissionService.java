package org.dromara.gz.common.service;

import org.dromara.gz.common.domain.dto.MpStaffPermission;
import org.dromara.gz.common.domain.entity.GzUser;

/**
 * mp 管理端权限底座服务（ADR-0004 / GZ-SYS-007）。
 *
 * <p>把"C 端微信用户绑定 sys_user → 加载其 ruoyi RBAC → 即时失效"这套逻辑收敛到一处，
 * 供 {@code WxLoginServiceImpl}（登录加载）、staff/me 端点（条件渲染）、admin 解绑/禁用钩子
 * （即时踢人）复用。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-SYS-007)
 */
public interface IMpStaffPermissionService {

    /**
     * 解析某 gz_user 的店员权限载荷（登录时调用）。
     *
     * <p>流程（ADR-0004 决策 1 + 安全红线"租户一致"）：</p>
     * <ol>
     *   <li>gz_user.staffUserId 为 NULL → 纯顾客（{@link MpStaffPermission#customer()}）。</li>
     *   <li>非空 → 直查 sys_user 校验：存在 + 未软删 + status 正常 + 与 gz_user 同租户。
     *       任一不满足 → 降级为纯顾客（不抛错，登录仍成功，只是不带管理权限）。</li>
     *   <li>校验通过 → 复用 ruoyi {@code PermissionService} 加载该 sys_user 的
     *       rolePermission + menuPermission（与 admin 端同一真源，不手写权限串）。</li>
     * </ol>
     *
     * @param gzUser 当前登录 mp 用户（含 staffUserId / tenantId）
     * @return 权限载荷（staff=true 时携带 RBAC 权限集）
     */
    MpStaffPermission resolve(GzUser gzUser);

    /**
     * 按 gz_user.id 解析店员权限（staff/me 端点用）。
     *
     * <p>先查 gz_user 实体（含 staffUserId / tenantId）再调 {@link #resolve(GzUser)}。
     * gz_user 不存在 → 纯顾客载荷。</p>
     *
     * @param gzUserId 当前登录 mp 用户 id（{@code LoginHelper.getUserId()}）
     * @return 权限载荷
     */
    MpStaffPermission resolveByGzUserId(Long gzUserId);

    /**
     * 即时失效：踢掉所有绑定指定店员 sys_user 的 mp 会话（ADR-0004 安全红线 AC5）。
     *
     * <p>当店员 sys_user 被 admin 禁用 / 被解绑（staff_user_id 置 NULL）时调用：反查所有绑定
     * 该 staffUserId 的 gz_user，逐个 {@code StpUtil.logout("app_user:" + gzUserId)} 注销其
     * 全部 mp token —— 携带店员权限的 token 立即失效，不靠"下次登录"。</p>
     *
     * <p>注意调用时序：解绑场景须"先反查 gz_user → 再 UPDATE 置 NULL → 再踢"，
     * 或先踢后改（本方法只负责踢，反查在内部完成，调用方传 staffUserId 即可）。</p>
     *
     * @param staffUserId 被禁用 / 解绑的 sys_user.user_id
     * @return 被踢的 mp 用户数
     */
    int kickoutByStaffUserId(Long staffUserId);

    /**
     * 解绑某 mp 用户的店员身份并即时踢出其会话（ADR-0004 安全红线 AC5）。
     *
     * <p>事务内：UPDATE gz_user.staff_user_id = NULL → 踢出该 gz_user 的全部 mp token。
     * 解绑后该用户即便持旧 token 也已被注销，重连须重新登录且按纯顾客（不再带管理权限）。</p>
     *
     * @param gzUserId 要解绑的 mp 用户 gz_user.id
     * @return true=该用户原为店员并已解绑+踢出；false=本就非店员（无操作）
     */
    boolean unbindStaffAndKickout(Long gzUserId);
}
