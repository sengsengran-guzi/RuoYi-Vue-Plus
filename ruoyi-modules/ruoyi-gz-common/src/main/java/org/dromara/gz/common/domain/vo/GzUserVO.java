package org.dromara.gz.common.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import org.dromara.gz.common.domain.entity.GzUser;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * gz_user 视图对象（admin / mp 共用）。
 *
 * <p><b>安全</b>：本 VO <b>不暴露 session_key</b>（doc/11 §2.1 已强约束 session_key 只走 Redis 不入库）。</p>
 *
 * <p>admin 列表 / 详情默认返回完整 openid + 完整 mobile（doc/11 §2.1 D6：甲方运营需要全量看
 * — 字段层不做脱敏，前端展示层按需打码）。mp `/app/gz/common/me` 返回当前用户基础信息时
 * 复用此 VO（敏感字段 mp 端展示前自行脱敏）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-SYS-003)
 */
@Data
@AutoMapper(target = GzUser.class)
public class GzUserVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键 */
    private Long id;

    /** 业务码 */
    private String userNo;

    /** 微信小程序级 openid */
    private String openid;

    /** 微信 unionid */
    private String unionid;

    /** 昵称 */
    private String nickname;

    /** 头像 URL */
    private String avatarUrl;

    /** 手机号 */
    private String mobile;

    /** 性别 0=未知 / 1=男 / 2=女 */
    private Integer gender;

    /** 注册来源 */
    private String registerSource;

    /** 注册时间 */
    private LocalDateTime registerTime;

    /** 最后登录时间 */
    private LocalDateTime lastLoginTime;

    /** 用户状态 browse_only / authorized / phone_bound */
    private String status;

    /** 是否禁用 0=正常 / 1=已禁用 */
    private Integer isDisabled;

    /** 创建时间（公共字段） */
    private LocalDateTime createTime;

    /** 备注 */
    private String remark;
}
