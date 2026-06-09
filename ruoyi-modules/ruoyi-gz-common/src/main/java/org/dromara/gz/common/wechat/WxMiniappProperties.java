package org.dromara.gz.common.wechat;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 微信小程序集成配置。
 *
 * <p>application.yml 节选：</p>
 * <pre>{@code
 * wx:
 *   miniapp:
 *     appid: ${WX_MA_APPID:wxMOCK}    # wxMOCK 表示走 dev 短路通道（不调微信 jscode2session）
 *     secret: ${WX_MA_SECRET:}
 *     session-key-ttl-seconds: 86400  # session_key 在 Redis 中的 TTL（24h，doc/10 §1.N6）
 *     token-ttl-seconds: 604800       # 业务 token TTL（7 天，doc/10 §1 状态机注释）
 * }</pre>
 *
 * <p>设计要点：</p>
 * <ul>
 *   <li>{@code appid} 默认 {@code "wxMOCK"} —— 没拿到真实 AppID 前安全可跑（dev）。</li>
 *   <li>真实 AppID 接入后由 {@code @ConditionalOnProperty} 自动切换 {@link WxLoginAdapter} 实现 —
 *       mock 通道 Bean 不加载，避免双 adapter 冲突。</li>
 *   <li>{@code session-key-ttl-seconds}：拼豆预约 / 用户编辑手机号场景的 {@code wx.getPhoneNumber}
 *       解密窗口（doc/10 §1.E3）。</li>
 * </ul>
 *
 * <p>关联文档：doc/10 §1 微信登录全流程 / doc/11 §2.1 gz_user 字段表 / GZ-SYS-002 任务卡</p>
 *
 * @author kevin-coder (sensenran-guzi)
 */
@Data
@Component
@ConfigurationProperties(prefix = "wx.miniapp")
public class WxMiniappProperties {

    /** 小程序 AppID，默认 "wxMOCK" 走 mock 通道。 */
    private String appid = "wxMOCK";

    /** 小程序 AppSecret，默认空（mock 通道不需要）。 */
    private String secret = "";

    /** session_key 在 Redis 中的 TTL（秒），默认 24h（仅解密手机号等微信能力用，与登录态解耦）。 */
    private long sessionKeyTtlSeconds = 86400L;

    /**
     * 业务 token TTL（秒），默认 <b>30 天</b>（ADR-0009 登录态长效）。
     *
     * <p>登录态真源 = sa-token 长效 token（独立于 session_key 的 24h）；过期由 mp 启动 / 401 时
     * 静默 wx.login → code2session 续期，用户无感（openid 静默可得）。</p>
     */
    private long tokenTtlSeconds = 2592000L;

    /**
     * 判断是否走 mock 通道。
     *
     * <p>规则：{@code appid} 为空 或 等于 {@code "wxMOCK"} 视为 mock。</p>
     */
    public boolean isMock() {
        return appid == null || appid.isBlank() || "wxMOCK".equalsIgnoreCase(appid);
    }
}
