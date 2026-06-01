package org.dromara.gz.common.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 店员绑定管理列表行 VO（admin owner 自助绑定，GZ-SYS-007 AC10）。
 *
 * <p>一行 = 一个 C 端微信用户（gz_user）+ 其当前绑定的店员 sys_user 快照（若有）。
 * owner 在 admin 据此「设为店员（选 sys_user）」/「解绑」。</p>
 *
 * <p><b>id 序列化</b>：{@code id} / {@code staffUserId} 走全局 ObjectMapper 的 ToStringSerializer
 * （Long → string），与跨层契约一致；admin 前端 TS 以 string 接收。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-SYS-007 AC10)
 */
@Data
public class StaffBindingVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** gz_user 主键 */
    private Long id;

    /** 业务码 U{yyyyMMdd}{6 位序号} */
    private String userNo;

    /** 微信 openid */
    private String openid;

    /** 昵称 */
    private String nickname;

    /** 手机号 */
    private String mobile;

    /** 头像 URL */
    private String avatarUrl;

    /** 当前绑定的店员 sys_user.user_id（NULL=纯顾客） */
    private Long staffUserId;

    /** 当前绑定店员的登录账号名（staffUserId 非空时填，已删/停用也展示便于排查） */
    private String staffUserName;

    /** 当前绑定店员的昵称 */
    private String staffNickName;

    /** 绑定店员是否当前有效（同租户 + 正常 + 未软删）；false=绑了但已失效（已停用/已删/跨租户） */
    private Boolean staffActive;
}
