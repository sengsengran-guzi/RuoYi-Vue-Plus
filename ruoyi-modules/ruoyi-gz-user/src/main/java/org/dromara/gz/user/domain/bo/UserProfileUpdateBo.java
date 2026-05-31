package org.dromara.gz.user.domain.bo;

import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 个人资料编辑请求体（GZ-USER-002）。
 *
 * <p>mp 端 PUT /app/gz/user/profile。仅昵称 / 头像 / 性别 —— 手机号走独立
 * bind-mobile（/app/gz/common/user/bind-mobile，微信 getPhoneNumber 明文）。</p>
 *
 * <p>字段口径：doc/11 §2.1 gz_user（nickname / avatar_url / gender）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-USER-002)
 */
@Data
public class UserProfileUpdateBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 昵称（≤ 32 字符；null 表示不修改） */
    @Size(max = 32, message = "昵称不能超过 32 个字符")
    private String nickname;

    /** 头像 URL（OSS / 微信 CDN URL；null 表示不修改） */
    @Size(max = 512, message = "头像地址过长")
    private String avatarUrl;

    /** 性别 0=未知 / 1=男 / 2=女（null 表示不修改） */
    private Integer gender;
}
