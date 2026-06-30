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

    /**
     * dev 本地手机号回落开关：真通道（真 AppID）下也跳过微信 getuserphonenumber，直接返默认测试号。
     *
     * <p>仅 application-dev.yml 置 true（默认 false）。用途：本地保留真登录（真 appid + 真 secret），
     * 但开发者工具模拟器拿不到可换取明文号的真实 code 时，用固定号 {@code 13800000000} 走通拼豆 / 资料
     * 页的手机号绑定，与 appid 模式解耦。staging/prod 不配 → false → 走真实换号。</p>
     */
    private boolean mockPhoneFallback = false;

    /**
     * dev 本地稳定 mock 用户：mock 登录通道（{@link #isMock()}）下忽略 wx.login 的 code，固定返回
     * {@code mock-<本值>} 作 openid，使本地所有登录（含 token 静默续期换新 code）恒为同一个测试用户。
     *
     * <p>仅 application-dev.yml 置非空（默认空 → 沿用 {@code mock-<code前8位>} 派生、换 code 即新用户的
     * 多用户测试口径）。背景：真 AppID 前端在开发者工具每次 {@code uni.login} 拿到的 code 都不同 → mock
     * 后端据 code 派生出不同 openid → 同一测试者被当成多个用户（续坐/同人判定按 user_id 全失灵）。置一个
     * 稳定值即可让 dev 单测试者 = 单用户，贴近生产「同设备 openid 稳定」。需多用户测试时置空。</p>
     */
    private String mockStableOpenid = "";

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
