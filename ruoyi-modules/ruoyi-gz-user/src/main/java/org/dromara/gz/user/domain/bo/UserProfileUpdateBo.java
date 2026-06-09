package org.dromara.gz.user.domain.bo;

import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 个人资料编辑请求体（GZ-USER-002 / GZ-USER-005 / GZ-USER-006）。
 *
 * <p>mp 端 PUT /app/gz/user/profile。昵称 / 头像 / 性别 / 微信号 —— 手机号走独立
 * bind-mobile（/app/gz/common/user/bind-mobile，微信 getPhoneNumber 明文）。</p>
 *
 * <p>字段口径：doc/11 §2.1 gz_user（nickname / avatar_image_id / gender / wechat_id）。</p>
 *
 * <p><b>头像采集口径（ADR-0009）</b>：头像真源是 {@link #avatarImageId}（COS 对象 id），不是裸 url。
 * mp chooseAvatar → 上传 COS（usage_type='user_avatar'）拿 fileId → 传 {@code avatarImageId}；
 * service 落 avatar_image_id + 重生成 avatar_url 缓存。{@code avatarUrl} 字段保留作兜底兼容
 * （旧客户端 / 非微信端直传 url），新链路用 avatarImageId。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-USER-002 / 005 / 006)
 */
@Data
public class UserProfileUpdateBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 昵称（≤ 32 字符；null 表示不修改；微信 &lt;input type="nickname"&gt; 采集，ADR-0009） */
    @Size(max = 32, message = "昵称不能超过 32 个字符")
    private String nickname;

    /**
     * 头像文件 id（FK gz_file_object.id；GZ-USER-006 / ADR-0009）。
     *
     * <p>头像真源：chooseAvatar 临时路径 → 上传 COS → 拿 fileId 传此字段。null 表示不修改。
     * service 落 avatar_image_id 并重生成 avatar_url 缓存（头像存 COS 不存裸 url）。</p>
     */
    private Long avatarImageId;

    /**
     * 头像 URL（兜底兼容；GZ-USER-006 后新链路用 {@link #avatarImageId}）。
     *
     * <p>仅当未传 avatarImageId 时作旧链路兼容（非微信端直传 / 历史客户端）；null 表示不修改。</p>
     */
    @Size(max = 512, message = "头像地址过长")
    private String avatarUrl;

    /** 性别 0=未知 / 1=男 / 2=女（null 表示不修改） */
    private Integer gender;

    /**
     * 微信号（GZ-USER-005；≤ 64 字符；null 表示不修改）。
     *
     * <p>用户手动填（微信无 API 可取）；格式不强校验（允许字母/数字/下划线/连字符等常见微信号字符）。</p>
     */
    @Size(max = 64, message = "微信号不能超过 64 个字符")
    private String wechatId;
}
