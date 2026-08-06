package org.dromara.gz.common.wechat;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 微信小程序集成配置（多小程序 / 多 appid，ADR-0019 §1）。
 *
 * <p><b>形态 A —— 多小程序（推荐，key = clientid）</b>：</p>
 * <pre>{@code
 * wx:
 *   miniapp:
 *     default-client-id: mp-applet-sensenran-guzi   # 无 clientid header 时（异步 / cron / curl）落到哪个 app
 *     apps:
 *       mp-applet-sensenran-guzi:
 *         appid: wx2f8b93e09703f07d
 *         secret: ${WX_MA_SECRET:}
 *       mp-applet-gz-jp:
 *         appid: wxMOCK                             # 该小程序单独走 mock，不影响上面那个走 real
 *     session-key-ttl-seconds: 86400
 *     token-ttl-seconds: 2592000
 * }</pre>
 *
 * <p><b>形态 B —— 单值（历史形态，向后兼容，prod 现用）</b>：不配 {@code apps}，只配标量
 * {@code appid}/{@code secret} —— 自动包装成 <code>{default-client-id: 单值}</code> 的单元素 Map，
 * 行为与多 appid 改造前完全一致。两种形态同时配置时 <b>{@code apps} 优先</b>，标量仅作为
 * 各 app 未显式覆盖字段（{@code mock-phone-fallback} / {@code mock-stable-openid} /
 * {@code register-source}）的继承源。</p>
 *
 * <p><b>real / mock 是每个小程序各自的属性</b>（不是进程的属性）：某个 app 的 {@code appid} 为空或
 * {@code wxMOCK} 即走 mock 通道，由 {@link WxAdapterDispatcher} 在<b>运行时</b>按当前请求 clientid 选择
 * 实现 —— 新小程序 dev 阶段没拿到 secret 走 mock 时，现小程序的真登录 / 真手机号 / 真 access_token /
 * 真发货上报不受任何影响。</p>
 *
 * <p><b>未知 clientid 一律抛错、不静默落到默认 app</b>（{@link #resolveApp(String)}）—— 静默兜底会让
 * 「新小程序用了旧 appid 的凭证」这类事故以「微信返 invalid code」的形态出现在运行期，根因极难定位。</p>
 *
 * <p>关联文档：ADR-0019 §1 / doc/10 §1 微信登录全流程 / doc/11 §2.1 gz_user 字段表</p>
 *
 * @author kevin-coder (sensenran-guzi)
 */
@Slf4j
@Data
@Component
@ConfigurationProperties(prefix = "wx.miniapp")
public class WxMiniappProperties implements InitializingBean {

    /** mock 通道哨兵 AppID —— 值为它（或空）即该小程序走 mock。 */
    public static final String MOCK_APPID = "wxMOCK";

    /**
     * 无 clientid 上下文时使用的默认 clientid（原 {@code WxLoginServiceImpl.DEFAULT_MP_CLIENT_ID} 常量）。
     *
     * <p>三类场景会落到它：单测 / curl 调试不带 header、{@code @Async} 发货上报与 cron（无请求上下文）、
     * 以及单值配置形态下的唯一 app key。配了 {@code apps} 时本值<b>必须</b>是其中一个 key，否则启动即失败
     * （见 {@link #afterPropertiesSet()}）—— 让配置错误在启动期暴露，而不是等异步发货上报时才炸。</p>
     */
    private String defaultClientId = "mp-applet-sensenran-guzi";

    /** 多小程序配置，key = 请求 header {@code clientid}（mp 端 http 拦截器统一注入）。 */
    private Map<String, MiniappApp> apps = new LinkedHashMap<>();

    /** 【单值向后兼容】小程序 AppID，默认 "wxMOCK" 走 mock 通道。{@code apps} 非空时忽略。 */
    private String appid = MOCK_APPID;

    /** 【单值向后兼容】小程序 AppSecret，默认空（mock 通道不需要）。{@code apps} 非空时忽略。 */
    private String secret = "";

    /**
     * dev 本地手机号回落开关：真通道（真 AppID）下也跳过微信 getuserphonenumber，直接返默认测试号。
     *
     * <p>仅 application-dev.yml 置 true（默认 false）。用途：本地保留真登录（真 appid + 真 secret），
     * 但开发者工具模拟器拿不到可换取明文号的真实 code 时，用固定号 {@code 13800000000} 走通拼豆 / 资料
     * 页的手机号绑定，与 appid 模式解耦。staging/prod 不配 → false → 走真实换号。</p>
     *
     * <p>作为<b>全局默认</b>：各 app 未显式配 {@code mock-phone-fallback} 时继承本值。</p>
     */
    private boolean mockPhoneFallback = false;

    /**
     * dev 本地稳定 mock 用户：mock 登录通道下忽略 wx.login 的 code，固定返回
     * {@code mock-<本值>} 作 openid，使本地所有登录（含 token 静默续期换新 code）恒为同一个测试用户。
     *
     * <p>仅 application-dev.yml 置非空（默认空 → 沿用 {@code mock-<code前8位>} 派生、换 code 即新用户的
     * 多用户测试口径）。背景：真 AppID 前端在开发者工具每次 {@code uni.login} 拿到的 code 都不同 → mock
     * 后端据 code 派生出不同 openid → 同一测试者被当成多个用户（续坐/同人判定按 user_id 全失灵）。置一个
     * 稳定值即可让 dev 单测试者 = 单用户，贴近生产「同设备 openid 稳定」。需多用户测试时置空。</p>
     *
     * <p>作为<b>全局默认</b>：各 app 未显式配 {@code mock-stable-openid} 时继承本值。多小程序并行 dev 时
     * 建议各 app 各配一个（否则两个小程序的 mock 用户 openid 相同、看起来像同一人）。</p>
     */
    private String mockStableOpenid = "";

    /**
     * 注册来源标记，写进 {@code gz_user.register_source}（GZ-SYS-023）。
     *
     * <p>多小程序后它不再是一个常量：admin 看用户详情时需要一眼分清这个人是从哪个小程序注册的。
     * {@code app_id} 只在两个小程序 appid 真的不同时才能区分，而 <b>dev 两个小程序都是
     * {@code wxMOCK}</b>（拼团 appid 未到手），此时唯一能区分来源的就是本值。</p>
     *
     * <p>作为<b>全局默认</b>：各 app 未显式配 {@code register-source} 时继承本值。默认
     * {@code mp_wechat} = 现小程序的历史取值 —— 单值形态（prod 现状）与现小程序的新注册行为
     * 因此与改造前逐字一致，存量行也无需回填。新增小程序<b>必须</b>在 yml 里给它配一个自己的值。</p>
     */
    private String registerSource = "mp_wechat";

    /** session_key 在 Redis 中的 TTL（秒），默认 24h（仅解密手机号等微信能力用，与登录态解耦）。全局，不分 app。 */
    private long sessionKeyTtlSeconds = 86400L;

    /**
     * 业务 token TTL（秒），默认 <b>30 天</b>（ADR-0009 登录态长效）。全局，不分 app。
     *
     * <p>登录态真源 = sa-token 长效 token（独立于 session_key 的 24h）；过期由 mp 启动 / 401 时
     * 静默 wx.login → code2session 续期，用户无感（openid 静默可得）。</p>
     */
    private long tokenTtlSeconds = 2592000L;

    /**
     * 启动期校验 + 配置摘要日志。
     *
     * <p>只在显式配了 {@code apps} 时校验 {@code default-client-id} 必须在其中 —— 单值形态（prod 现状）
     * 天然满足，不受影响。</p>
     */
    @Override
    public void afterPropertiesSet() {
        if (apps != null && !apps.isEmpty() && !apps.containsKey(defaultClientId)) {
            throw new IllegalStateException(
                "wx.miniapp.default-client-id=" + defaultClientId + " 不在 wx.miniapp.apps 的 key 里"
                    + apps.keySet() + "；无请求上下文的调用（异步发货上报 / cron / 单测）将无法解析小程序，"
                    + "请把 default-client-id 指向其中一个 clientid");
        }
        resolveApps().forEach((clientId, app) -> log.info(
            "[wx-miniapp] 小程序注册 clientid={} appid={} channel={} registerSource={}",
            clientId, app.getAppid(), app.isMock() ? "mock" : "real", app.getRegisterSource()));
    }

    /**
     * 归一化后的小程序视图：{@code apps} 非空时按其展开（并回填继承字段），否则把单值标量包装成
     * <code>{defaultClientId: 单值}</code> 单元素 Map。
     *
     * <p>每次调用重新物化（不缓存）—— 保证单测里改完标量立刻生效，且调用点全部是 IO 级操作
     * （微信 HTTP / Redis），这点开销可忽略。</p>
     *
     * @return clientid → 小程序配置（只读）
     */
    public Map<String, MiniappApp> resolveApps() {
        Map<String, MiniappApp> resolved = new LinkedHashMap<>(4);
        if (apps != null && !apps.isEmpty()) {
            apps.forEach((clientId, app) -> resolved.put(clientId, materialize(clientId, app)));
        } else {
            resolved.put(defaultClientId, legacyApp());
        }
        return Collections.unmodifiableMap(resolved);
    }

    /**
     * 按 clientid 解析小程序配置。
     *
     * @param clientId 请求 header {@code clientid}；为空 → 回落 {@link #getDefaultClientId()}
     * @return 该小程序的配置
     * @throws ServiceException clientid 未在 {@code wx.miniapp.apps} 中配置（<b>不静默落默认 app</b>）
     */
    public MiniappApp resolveApp(String clientId) {
        String key = (clientId == null || clientId.isBlank()) ? defaultClientId : clientId;
        MiniappApp app = resolveApps().get(key);
        if (app == null) {
            throw new ServiceException("未配置的小程序客户端 clientid=" + key
                + "，请在 wx.miniapp.apps 中登记（已配置：" + resolveApps().keySet() + "）");
        }
        return app;
    }

    /** 默认小程序（无 clientid 上下文时用）。 */
    public MiniappApp resolveDefaultApp() {
        return resolveApp(null);
    }

    /**
     * 默认小程序是否走 mock 通道（历史 API 保留；按 clientid 判定请用 {@link MiniappApp#isMock()}）。
     */
    public boolean isMock() {
        return resolveDefaultApp().isMock();
    }

    /** 单值标量 → MiniappApp（向后兼容包装）。 */
    private MiniappApp legacyApp() {
        MiniappApp app = new MiniappApp();
        app.setClientId(defaultClientId);
        app.setAppid(appid);
        app.setSecret(secret);
        app.setMockPhoneFallback(mockPhoneFallback);
        app.setMockStableOpenid(mockStableOpenid);
        app.setRegisterSource(registerSource);
        return app;
    }

    /** 回填 clientId + 继承全局默认（未显式配置的 dev 开关走标量值）。 */
    private MiniappApp materialize(String clientId, MiniappApp configured) {
        MiniappApp app = new MiniappApp();
        app.setClientId(clientId);
        app.setAppid(configured.getAppid());
        app.setSecret(configured.getSecret());
        app.setMockPhoneFallback(configured.getMockPhoneFallback() != null
            ? configured.getMockPhoneFallback() : mockPhoneFallback);
        app.setMockStableOpenid(configured.getMockStableOpenid() != null
            ? configured.getMockStableOpenid() : mockStableOpenid);
        app.setRegisterSource(configured.getRegisterSource() != null
            ? configured.getRegisterSource() : registerSource);
        return app;
    }

    /**
     * 单个小程序的配置（一个 appid 一条）。
     */
    @Data
    public static class MiniappApp {

        /** 所属 clientid（由 {@link #resolveApps()} 回填，配置里不用写）。 */
        private String clientId;

        /** 小程序 AppID；空 或 {@code wxMOCK} → 本小程序走 mock 通道。 */
        private String appid = MOCK_APPID;

        /** 小程序 AppSecret（mock 通道不需要）。 */
        private String secret = "";

        /** dev 手机号回落；null = 继承 {@code wx.miniapp.mock-phone-fallback}。 */
        private Boolean mockPhoneFallback;

        /** dev 稳定 mock openid；null = 继承 {@code wx.miniapp.mock-stable-openid}。 */
        private String mockStableOpenid;

        /**
         * 本小程序注册的用户写进 {@code gz_user.register_source} 的值（GZ-SYS-023）；
         * null = 继承 {@code wx.miniapp.register-source}（默认 {@code mp_wechat}）。
         *
         * <p>VARCHAR(32)，新增小程序务必配一个自己的值（否则与现小程序同名、admin 分不清来源）。</p>
         */
        private String registerSource;

        /** 本小程序是否走 mock 通道（appid 空 或 == wxMOCK）。 */
        public boolean isMock() {
            return appid == null || appid.isBlank() || MOCK_APPID.equalsIgnoreCase(appid);
        }

        /** dev 手机号回落是否开启（null 视为 false —— 归一化后不会为 null）。 */
        public boolean isMockPhoneFallbackEnabled() {
            return Boolean.TRUE.equals(mockPhoneFallback);
        }
    }
}
