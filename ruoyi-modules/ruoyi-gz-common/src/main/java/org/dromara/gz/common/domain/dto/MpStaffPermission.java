package org.dromara.gz.common.domain.dto;

import lombok.Builder;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.Collections;
import java.util.Set;

/**
 * mp 店员权限载荷（ADR-0004 mp 管理端权限底座）。
 *
 * <p>由 {@code MpStaffPermissionService#resolve(GzUser)} 计算：</p>
 * <ul>
 *   <li>{@code staff=false} — gz_user.staff_user_id 为 NULL（纯顾客），或绑定的 sys_user 校验不通过
 *       （跨租户 / 禁用 / 软删 / 不存在）。此时 rolePermission/menuPermission 为空集 → mp 登录降级为纯顾客。</li>
 *   <li>{@code staff=true} — 绑定 sys_user 合法，rolePermission/menuPermission 来自 ruoyi RBAC
 *       （复用 {@code PermissionService}，与 admin 端同一真源）。</li>
 * </ul>
 *
 * @author kevin-coder (sensenran-guzi · GZ-SYS-007)
 */
@Data
@Builder
public class MpStaffPermission implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 是否为合法店员（绑定 + 同租户 + 未禁用 + 未软删）。 */
    private boolean staff;

    /** 绑定的 sys_user.user_id（staff=false 时为 null）。 */
    private Long staffUserId;

    /** 店员显示名（绑定 sys_user 的 nick_name；staff=false 时 null）。 */
    private String staffName;

    /** ruoyi 角色权限集（role key，如 owner / staff）。staff=false 时空集。 */
    private Set<String> rolePermission;

    /** ruoyi 菜单权限集（perm key，如 gz:bean:booking:verify）。staff=false 时空集。 */
    private Set<String> menuPermission;

    /** 纯顾客（非店员）的空载荷。 */
    public static MpStaffPermission customer() {
        return MpStaffPermission.builder()
            .staff(false)
            .staffUserId(null)
            .rolePermission(Collections.emptySet())
            .menuPermission(Collections.emptySet())
            .build();
    }
}
