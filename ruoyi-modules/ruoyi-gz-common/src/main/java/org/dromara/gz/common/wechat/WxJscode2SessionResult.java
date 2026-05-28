package org.dromara.gz.common.wechat;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 微信 jscode2session 接口的解构结果。
 *
 * <p>对应微信开放接口 {@code GET https://api.weixin.qq.com/sns/jscode2session?...} 的响应字段：</p>
 * <pre>{@code
 * {
 *   "openid":     "ox-xxxxxx",       // 小程序级用户唯一标识
 *   "session_key":"xxxxxxxxxx==",    // 会话密钥（用于解 wx.getPhoneNumber 等）
 *   "unionid":    "ux-xxxxxx",       // 仅当甲方公司绑公众号/开放平台时返回
 *   "errcode":    0,
 *   "errmsg":     "ok"
 * }
 * }</pre>
 *
 * <p>本类只承载 ok 路径的 3 个核心字段；错误路径由 {@link WxLoginAdapter} 抛 ServiceException。</p>
 *
 * <p>关联文档：doc/10 §1.N4（jscode2session 节点） / doc/11 §2.1（gz_user.openid/unionid 字段）</p>
 *
 * @author kevin-coder (sensenran-guzi)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WxJscode2SessionResult implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 小程序级 openid（必返）。 */
    private String openid;

    /** 会话密钥（必返；不下发前端，仅后端缓存 Redis 用于解密手机号等）。 */
    private String sessionKey;

    /** unionid（仅当 AppID 绑公众号/开放平台时返回，可能为 null）。 */
    private String unionid;
}
