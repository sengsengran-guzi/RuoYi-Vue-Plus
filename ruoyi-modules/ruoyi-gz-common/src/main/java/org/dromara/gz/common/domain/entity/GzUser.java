package org.dromara.gz.common.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;
import org.dromara.common.tenant.core.TenantEntity;

import java.io.Serial;
import java.time.LocalDateTime;

/**
 * gz_user — C 端微信用户主表 entity（GZ-SYS-003 落 DDL 版）。
 *
 * <p>字段口径权威：doc/11 §2.1 gz_user。</p>
 *
 * <p><b>关键字段语义</b>：</p>
 * <ul>
 *   <li>{@code openid} — UNIQUE(tenant_id, openid)，微信小程序级唯一</li>
 *   <li>{@code unionid} — 甲方未绑公众号/开放平台时为 null</li>
 *   <li>{@code sessionKey} — <b>不在 DB</b>（doc/11 §2.1 明确走 Redis），entity 无此字段</li>
 *   <li>{@code status} — VARCHAR(16) 状态枚举：`browse_only` / `authorized` / `phone_bound`（`guest` 不落库）</li>
 *   <li>{@code isDisabled} — 后台禁用标记（与 status 解耦）</li>
 *   <li>{@code registerTime} — 业务语义独立（不被 mybatis-plus FieldFill 覆盖；与 create_time 都保留）</li>
 * </ul>
 *
 * <p><b>不预留 V1.1 字段</b>（doc/11 §2.1 D5）：等级 / 积分 / 火花券 / 邀请码等 V1.1 用 ALTER 加。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-SYS-003)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("gz_user")
public class GzUser extends TenantEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键（DB AUTO_INCREMENT） */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 业务码 U{yyyyMMdd}{6 位序号} — UNIQUE(tenant_id, user_no) */
    private String userNo;

    /** 微信小程序级 openid — UNIQUE(tenant_id, openid) */
    private String openid;

    /** 微信 unionid（甲方未绑公众号/开放平台时为 null） */
    private String unionid;

    /** 昵称（chooseAvatar 时代用 &lt;input type="nickname"&gt; 一次性采集，用户可编辑覆盖；ADR-0009） */
    private String nickname;

    /**
     * 头像可渲染 URL 缓存（doc/11 §2.1 / ADR-0009）。
     *
     * <p><b>V1.2 改语义</b>：头像真源 = {@link #avatarImageId}（COS 对象）；本字段只是按 image_id
     * 对应 COS 对象拼出的可渲染 URL 缓存（读取时后端按 avatar_image_id 重生成 1h 签名 URL 回填）。
     * 不再存微信 CDN 临时 URL（临时 URL 会失效）。avatarImageId 为 null（未采集）时可空。</p>
     */
    private String avatarUrl;

    /**
     * 头像文件 id（FK gz_file_object.id；GZ-USER-006 / ADR-0009 / doc/11 §2.1 F2.6）。
     *
     * <p>chooseAvatar 拿到的临时头像路径上传腾讯云 COS（usage_type='user_avatar'）后落库的 file_object id。
     * <b>头像落 COS 不存裸 url</b>（跨层契约 #5 + 强约束 #12）；渲染时按本 id 调
     * {@code IGzFileService#getPresignedUrl} 生成 1h 签名 URL。</p>
     */
    private Long avatarImageId;

    /** 手机号（拼豆预约时 getPhoneNumber 强收集） */
    private String mobile;

    /**
     * 微信号（GZ-USER-005 / ADR-0009 / doc/11 §2.1）。
     *
     * <p><b>用户手动填</b>：微信不开放任何 API 查微信号（区别于手机号有 getPhoneNumber 一键授权）。
     * 无 UNIQUE（一个微信号可被多人填）；拼豆付款前采集便于门店联系，资料页可编辑。</p>
     */
    private String wechatId;

    /** 性别 0=未知 / 1=男 / 2=女（对齐 sys_dict sys_user_sex） */
    private Integer gender;

    /** 注册来源（V1.0/V1.1 固定 'mp_wechat'） */
    private String registerSource;

    /** 注册时间（首次 §1.N5 时写，不随后续登录变化） */
    private LocalDateTime registerTime;

    /** 最后登录时间（每次 §1.N6 更新） */
    private LocalDateTime lastLoginTime;

    /** 用户状态 browse_only / authorized / phone_bound（doc/10 §1 状态机） */
    private String status;

    /** 是否禁用 0=正常 / 1=已禁用 */
    private Integer isDisabled;

    /**
     * 绑定的 ruoyi 店员账号 sys_user.user_id（ADR-0004 mp 管理端权限底座）。
     *
     * <p>NULL = 纯顾客（mp 登录不加载任何 RBAC）；非空 = 店员，登录时把该 sys_user 的
     * 角色 + 菜单权限载入当前 app_user 会话，使 mp 管理端点能用标准 {@code @SaCheckPermission}。
     * 绑定的 sys_user 须同租户(1001) + status 正常 + 未禁用，否则登录时降级为纯顾客
     * （校验在 {@code WxLoginServiceImpl} / {@code MpStaffPermissionService}）。</p>
     */
    private Long staffUserId;

    /** 软删标志（0=正常 / 2=删除，对齐 ruoyi） */
    @TableLogic
    private String delFlag;
}
