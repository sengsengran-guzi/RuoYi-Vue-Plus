package org.dromara.gz.common.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 可绑定的店员 sys_user 候选 VO（admin owner 绑定时选择，GZ-SYS-007 AC10）。
 *
 * <p>owner「设为店员」时从候选列表选一个 sys_user。候选 = 同租户(1001) + 正常 + 未软删的 sys_user。
 * 不暴露密码 / 邮箱等敏感字段，只回 id + 账号名 + 昵称供下拉选择。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-SYS-007 AC10)
 */
@Data
public class StaffCandidateVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** sys_user.user_id（Long → string 序列化） */
    private Long userId;

    /** 登录账号名 */
    private String userName;

    /** 昵称 */
    private String nickName;
}
