package org.dromara.gz.common.domain.vo;

import lombok.Builder;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.Collections;
import java.util.Set;

/**
 * mp 「我是不是店员 + 我有哪些管理权限」VO（ADR-0004 / GZ-SYS-007）。
 *
 * <p>{@code GET /app/gz/staff/me} 返回，供 mp 个人中心条件渲染「管理」区块 + 各管理入口显隐。</p>
 *
 * <p><b>安全</b>：<b>不暴露</b> staffUserId（内部 sys_user id）/ openid 等给 mp 前端；只回 isStaff
 * + perms（前端按 perms.includes('gz:bean:booking:verify') 判定具体入口显隐）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-SYS-007)
 */
@Data
@Builder
public class StaffMeVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 当前 mp 用户是否为绑定店员（驱动「管理」区块整体显隐）。 */
    private boolean staff;

    /** 店员显示名（绑定 sys_user 的 nick_name；纯顾客为 null）。 */
    private String staffName;

    /** 角色 key 集合（如 owner / staff）。 */
    private Set<String> roles;

    /** 菜单权限 key 集合（如 gz:bean:booking:verify），前端据此判定各管理入口显隐。 */
    private Set<String> perms;

    /** 纯顾客（非店员）响应。 */
    public static StaffMeVO customer() {
        return StaffMeVO.builder()
            .staff(false)
            .staffName(null)
            .roles(Collections.emptySet())
            .perms(Collections.emptySet())
            .build();
    }
}
