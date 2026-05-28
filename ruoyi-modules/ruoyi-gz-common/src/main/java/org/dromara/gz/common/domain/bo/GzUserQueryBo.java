package org.dromara.gz.common.domain.bo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * gz_user admin 列表查询条件（GZ-SYS-003）。
 *
 * <p>admin 端 GET /system/gz/user/list 接收的查询参数（前缀 fuzzy 匹配 openid / mobile / nickname，
 * 等值匹配 status / isDisabled，区间匹配 registerTime / lastLoginTime）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-SYS-003)
 */
@Data
public class GzUserQueryBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** openid 前缀模糊（前 8 位足够定位单用户） */
    private String openid;

    /** 昵称模糊 */
    private String nickname;

    /** 手机号模糊 */
    private String mobile;

    /** 用户状态 browse_only / authorized / phone_bound */
    private String status;

    /** 是否禁用 0/1 */
    private Integer isDisabled;

    /** 注册时间范围 — 开始 */
    private LocalDateTime registerTimeStart;

    /** 注册时间范围 — 结束 */
    private LocalDateTime registerTimeEnd;

    /** 最后登录时间范围 — 开始 */
    private LocalDateTime lastLoginTimeStart;

    /** 最后登录时间范围 — 结束 */
    private LocalDateTime lastLoginTimeEnd;
}
