package org.dromara.gz.common.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 微信小程序登录请求 DTO（GZ-SYS-002 任务卡 AC 2 入参）。
 *
 * <p>调用入口：{@code POST /app/gz/common/auth/wx-login}</p>
 *
 * <p>典型 body：</p>
 * <pre>{@code
 * {
 *   "code":      "wx-login-temp-code-12345abc",
 *   "nickName":  "微信昵称",        // optional（用户拒绝授权 wx.getUserProfile 时为空）
 *   "avatarUrl": "https://wx.qlogo.cn/..."  // optional
 * }
 * }</pre>
 *
 * <p>{@code clientId} 不从 body 接收 — 由 ruoyi 已有 header {@code clientid} 注入（参见
 * {@code security.config.SecurityConfig}），mp 端拦截器统一加，不需要业务代码处理。</p>
 *
 * @author kevin-coder (sensenran-guzi)
 */
@Data
public class WxLoginRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** wx.login() 返回的临时 code（≤32 位，5 分钟有效）。 */
    @NotBlank(message = "微信登录 code 不能为空")
    @Size(max = 256, message = "code 长度异常")
    private String code;

    /** 微信授权拿到的昵称（用户拒绝授权 wx.getUserProfile 时可空，由后端 fallback 为 "微信用户"）。 */
    @Size(max = 64, message = "昵称长度不能超过 64")
    private String nickName;

    /** 微信授权拿到的头像 URL（同上，可空）。 */
    @Size(max = 512, message = "头像 URL 长度不能超过 512")
    private String avatarUrl;
}
