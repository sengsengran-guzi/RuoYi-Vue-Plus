package org.dromara.gz.common.domain.vo;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Builder;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 微信小程序登录响应 VO。
 *
 * <p>调用出口：{@code POST /app/gz/common/auth/wx-login} 200 响应（包在 ruoyi {@code R<T>} 里）。</p>
 *
 * <p>命名约定：</p>
 * <ul>
 *   <li>{@code token} / {@code expiresIn}（驼峰）— 对齐 mp 端 unibest 内置 ISingleTokenRes 类型，
 *       避免 mp 端做字段适配（dongjiaoshan 用 access_token/expire_in OAuth2 风格需 mp 适配，本项目不沿用）</li>
 *   <li>{@code userId} 序列化为 string —— 跨 fe+be 契约必背 #1（Long ↔ JS number 精度丢失，
 *       本项目 kevin-coder.md 跨层契约必背）</li>
 *   <li>{@code openid} / {@code nickName} / {@code avatarUrl} 用于 mp 端首屏直接渲染我的页头像区</li>
 * </ul>
 *
 * <p>不返回 {@code session_key}（doc/11 §2.1 明确：仅后端 Redis 持有，不下发前端）。</p>
 *
 * <p>关联文档：doc/10 §1.N6 / doc/11 §2.1 / GZ-SYS-002 任务卡 AC 4</p>
 *
 * @author kevin-coder (sensenran-guzi)
 */
@Data
@Builder
public class WxLoginVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** sa-token 业务 token（Bearer ${token}）。 */
    private String token;

    /** token 有效期（秒），默认 7 天（{@link org.dromara.gz.common.wechat.WxMiniappProperties#getTokenTtlSeconds()}）。 */
    private Long expiresIn;

    /** 用户 ID（序列化为 string，跨 fe+be 契约必背）。 */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long userId;

    /** 微信 openid（mp 端可缓存用于幂等场景，不暴露给 UI）。 */
    private String openid;

    /** 昵称（mp 端我的页直接显示）。 */
    @JsonProperty("nickName")
    private String nickName;

    /** 头像 URL（mp 端我的页直接显示）。 */
    @JsonProperty("avatarUrl")
    private String avatarUrl;
}
