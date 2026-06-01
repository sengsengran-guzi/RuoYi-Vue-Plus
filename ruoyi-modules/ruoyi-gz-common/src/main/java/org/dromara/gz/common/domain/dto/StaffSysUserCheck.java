package org.dromara.gz.common.domain.dto;

import lombok.Data;

/**
 * 绑定 sys_user 的校验快照（ADR-0004 / GZ-SYS-007）。
 *
 * <p>由 {@code MpStaffSysUserMapper#selectCheckById} 直查 sys_user 返回，供
 * {@code MpStaffPermissionService} 做"同租户 + 未禁用 + 未软删"校验。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-SYS-007)
 */
@Data
public class StaffSysUserCheck {

    /** sys_user.user_id */
    private Long userId;

    /** 登录账号名（日志用） */
    private String userName;

    /** 昵称（mp 端展示店员身份用） */
    private String nickName;

    /** 租户 id（须与 gz_user 同租户 = '1001'） */
    private String tenantId;

    /** 账号状态 '0'=正常 / '1'=停用 */
    private String status;

    /** 软删标志 '0'=正常 / '1'=已删 */
    private String delFlag;
}
