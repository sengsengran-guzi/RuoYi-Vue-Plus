package org.dromara.gz.common.wechat;

import org.dromara.common.core.exception.ServiceException;
import org.dromara.gz.common.pay.shipping.WxShippingClient.UploadCommand;
import org.dromara.gz.common.pay.shipping.impl.WxMockShippingClient;
import org.dromara.gz.common.pay.shipping.impl.WxRealShippingClient;
import org.dromara.gz.common.wechat.WxMiniappProperties.MiniappApp;
import org.dromara.gz.common.wechat.impl.WxMockAccessTokenManager;
import org.dromara.gz.common.wechat.impl.WxMockLoginAdapter;
import org.dromara.gz.common.wechat.impl.WxMockPhoneAdapter;
import org.dromara.gz.common.wechat.impl.WxRealAccessTokenManager;
import org.dromara.gz.common.wechat.impl.WxRealLoginAdapter;
import org.dromara.gz.common.wechat.impl.WxRealPhoneAdapter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link WxAdapterDispatcher} 单测 —— real / mock 按 clientid <b>运行时</b>分派（GZ-SYS-020 / ADR-0019 §1）。
 *
 * <p>改造前 real / mock 由 8 处启动期条件 Bean 全局互斥，同一进程只能整体 real 或整体 mock；本测试锁死
 * 新模型：<b>两套实现同时在容器里</b>，同一个 JVM 内 A 小程序走 real、B 小程序走 mock 互不干扰。</p>
 *
 * <p>不触网：只对 mock 侧真调方法（real 侧只断言选中的实现类型，调用会打微信 HTTP）。</p>
 *
 * @author kevin-coder (sensenran-guzi)
 */
@Tag("dev")
class WxLoginAdapterDispatchTest {

    private static final String CID_GUZI = "mp-applet-sensenran-guzi";
    private static final String CID_JP = "mp-applet-gz-jp";
    private static final String REAL_APPID = "wx2f8b93e09703f07d";
    private static final String CLIENT_HEADER = "clientid";

    /**
     * plus-ui 后台的 PC 认证客户端 id（{@code sys_client} 种子数据 / plus-ui .env 的 VITE_APP_CLIENT_ID）。
     * 它<b>不是</b>小程序，但 plus-ui 给每个后台请求都注这个 header —— 共享能力必须容得下它。
     */
    private static final String ADMIN_PC_CLIENT_ID = "e5cd7e4891bf95d1d19206ce24a7b32e";

    private WxMiniappProperties properties;
    private WxAdapterDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        properties = new WxMiniappProperties();
        // 旧小程序 = real（prod 现状），新小程序 = mock（还没拿到 secret）
        MiniappApp guzi = new MiniappApp();
        guzi.setAppid(REAL_APPID);
        guzi.setSecret("guzi-secret");
        MiniappApp jp = new MiniappApp();
        jp.setAppid("wxMOCK");
        jp.setMockStableOpenid("jp-tester");
        properties.getApps().put(CID_GUZI, guzi);
        properties.getApps().put(CID_JP, jp);
        properties.setDefaultClientId(CID_GUZI);

        WxAppResolver resolver = new WxAppResolver(properties);
        WxRealAccessTokenManager realToken = new WxRealAccessTokenManager(resolver);
        dispatcher = new WxAdapterDispatcher(
            resolver,
            new WxMockLoginAdapter(resolver),
            new WxRealLoginAdapter(resolver),
            new WxMockPhoneAdapter(),
            new WxRealPhoneAdapter(realToken, resolver),
            new WxMockAccessTokenManager(),
            realToken,
            new WxMockShippingClient(),
            new WxRealShippingClient(realToken, new WxDeliveryListResolver(realToken)));
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    /** 模拟一个带 clientid header 的请求上下文。 */
    private void givenRequestWithClientId(String clientId) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (clientId != null) {
            request.addHeader(CLIENT_HEADER, clientId);
        }
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @Test
    @DisplayName("AC: 旧小程序（real appid）→ 四种能力全部选 real 实现")
    void realClient_picksRealImplementations() {
        givenRequestWithClientId(CID_GUZI);

        assertInstanceOf(WxRealLoginAdapter.class, dispatcher.loginAdapter());
        assertInstanceOf(WxRealPhoneAdapter.class, dispatcher.phoneAdapter());
        assertInstanceOf(WxRealAccessTokenManager.class, dispatcher.accessTokenManager());
        assertInstanceOf(WxRealShippingClient.class, dispatcher.shippingClient());
        assertEquals("real", dispatcher.channel());
        assertEquals(REAL_APPID, dispatcher.currentApp().getAppid());
    }

    @Test
    @DisplayName("AC: 新小程序（wxMOCK）→ 四种能力全部选 mock 实现，且不影响上面那个 real")
    void mockClient_picksMockImplementations() {
        givenRequestWithClientId(CID_JP);

        assertInstanceOf(WxMockLoginAdapter.class, dispatcher.loginAdapter());
        assertInstanceOf(WxMockPhoneAdapter.class, dispatcher.phoneAdapter());
        assertInstanceOf(WxMockAccessTokenManager.class, dispatcher.accessTokenManager());
        assertInstanceOf(WxMockShippingClient.class, dispatcher.shippingClient());
        assertEquals("mock", dispatcher.channel());

        // 同一个 dispatcher 实例，换个 clientid 立刻切回 real —— 证明不是启动期定死
        givenRequestWithClientId(CID_GUZI);
        assertInstanceOf(WxRealLoginAdapter.class, dispatcher.loginAdapter());
        assertEquals("real", dispatcher.channel());
    }

    @Test
    @DisplayName("AC: mock 小程序走门面 code2Session → 短路返 mock 结果（不触网），且用本 app 的 stable openid")
    void mockClient_code2SessionShortCircuits() {
        givenRequestWithClientId(CID_JP);

        WxJscode2SessionResult result = dispatcher.code2Session("any-code-123456");

        assertEquals("mock-jp-tester", result.getOpenid());
        assertEquals("mock-union-jp-tester", result.getUnionid());
        assertEquals("mock-session-any-code-123456", result.getSessionKey());
    }

    @Test
    @DisplayName("AC: mock 小程序的手机号 / access_token / 发货上报门面转发全部落 mock 实现")
    void mockClient_facadeDelegatesEveryCapability() {
        givenRequestWithClientId(CID_JP);

        assertEquals("13800000000", dispatcher.code2Phone("phone-code"));
        assertEquals("mock_access_token", dispatcher.getToken(false));
        assertTrue(dispatcher.uploadShippingInfo(new UploadCommand(
            "4200002222202606060000000001", "mock-openid", 3, "拼豆座位")).success());
    }

    @Test
    @DisplayName("无请求上下文（@Async 发货 / cron / 单测）→ 落 default-client-id 对应的小程序")
    void noRequestContext_fallsBackToDefaultClient() {
        RequestContextHolder.resetRequestAttributes();

        assertEquals(CID_GUZI, new WxAppResolver(properties).currentClientId());
        assertInstanceOf(WxRealLoginAdapter.class, dispatcher.loginAdapter());

        // 默认小程序切成 mock 的那个 → 无上下文时就走 mock
        properties.setDefaultClientId(CID_JP);
        assertInstanceOf(WxMockLoginAdapter.class, dispatcher.loginAdapter());
    }

    @Test
    @DisplayName("AC: mp 专属能力（登录/手机号）header 带未登记 clientid → 抛错，不静默按默认 app 发凭证")
    void unknownClientId_throwsOnMpOnlyCapabilities() {
        givenRequestWithClientId("mp-applet-unknown");

        ServiceException ex = assertThrows(ServiceException.class, () -> dispatcher.loginAdapter());
        assertTrue(ex.getMessage().contains("mp-applet-unknown"));

        assertThrows(ServiceException.class, () -> dispatcher.code2Session("any-code"));
        assertThrows(ServiceException.class, () -> dispatcher.phoneAdapter());
        assertThrows(ServiceException.class, () -> dispatcher.channel());
    }

    @Test
    @DisplayName("★回归: 后台 admin 的 PC clientid 调共享能力（发货补报 / access_token）→ 落默认 app，绝不能抛错")
    void adminPcClientId_sharedCapabilitiesFallBackInsteadOfThrowing() {
        // plus-ui 给每个后台请求都注 clientid=e5cd7e...（PC 认证客户端，不是小程序）
        // 后台「发货信息手动补报」POST /system/gz/pay/shipping/{id}/retry 就带着它进到这里。
        // 用严格口径会报「未配置的小程序客户端」→ 补报按钮全挂（该按钮是发货上报唯一的人工兜底）。
        givenRequestWithClientId(ADMIN_PC_CLIENT_ID);

        // 关键：解析这一步不许抛 ServiceException（改造前后台补报就是这么挂的）
        assertDoesNotThrow(() -> dispatcher.shippingClient());
        assertDoesNotThrow(() -> dispatcher.accessTokenManager());
        // 落的是 default-client-id 对应的小程序（本 fixture 里默认那个是 real）
        // 不真调 uploadShippingInfo —— real 实现会去打微信 / Redis，那是集成测试的事
        assertInstanceOf(WxRealShippingClient.class, dispatcher.shippingClient());
        assertInstanceOf(WxRealAccessTokenManager.class, dispatcher.accessTokenManager());
    }

    @Test
    @DisplayName("★回归: 默认 app 是 mock 时，后台 PC clientid 走完整条上报（dev 后台补报按钮可跑通）")
    void adminPcClientId_fallsBackToMockWhenDefaultAppIsMock() {
        properties.setDefaultClientId(CID_JP);
        givenRequestWithClientId(ADMIN_PC_CLIENT_ID);

        assertInstanceOf(WxMockShippingClient.class, dispatcher.shippingClient());
        assertInstanceOf(WxMockAccessTokenManager.class, dispatcher.accessTokenManager());
        assertEquals("mock_access_token", dispatcher.getToken(false));
        assertTrue(dispatcher.uploadShippingInfo(new UploadCommand(
            "4200002222202606060000000002", "mock-openid", 3, "拼豆座位")).success());
    }

    @Test
    @DisplayName("★★核心不变量: 显式传入的 clientId 压过请求 header —— 发货上报按「任务行上存的归属」选 appid")
    void explicitClientId_overridesRequestHeader() {
        // 这是多小程序共用一个后端时最贵的一条不变量，之前零覆盖。
        //
        // 为什么重要：发货上报真正发生在 @Async / cron / admin 补报线程里，那里**根本没有请求上下文**；
        // 有上下文时（admin 补报）header 里装的还是操作者当前那个客户端，跟这笔单属于谁毫无关系。
        // 所以 doUpload 是拿「发货任务行上存的 client_id」调 shippingClient(clientId) 的。
        // 一旦这个重载改成忽略入参、回落去读上下文，就是「B 小程序的订单用 A 的 appid 报上去」：
        // access_token 是 appid 维度凭证，微信会恒回 10060001「支付单不存在」，而且**重试永远好不了**
        // （每次重试都还是拿错 token）。这个故障在 mock 通道下完全照不出来。
        //
        // fixture 里 CID_GUZI=real、CID_JP=mock，所以「选错了」会体现为实现类整个换掉，一眼可辨。

        // ① 上下文是谷子宇宙（real），但这笔单属于拼团 → 必须给拼团那套（mock）
        givenRequestWithClientId(CID_GUZI);
        assertInstanceOf(WxMockShippingClient.class, dispatcher.shippingClient(CID_JP),
            "行上 clientId=拼团，却按 header(谷子宇宙) 选了实现 —— 上报会拿错 appid 的 token");

        // ② 反向同样成立（防止实现写成「固定偏向 mock」也能过 ①）
        givenRequestWithClientId(CID_JP);
        assertInstanceOf(WxRealShippingClient.class, dispatcher.shippingClient(CID_GUZI),
            "行上 clientId=谷子宇宙，却按 header(拼团) 选了实现");

        // ③ 无请求上下文（@Async / cron 的真实处境）仍按入参选 —— 这才是上报的主场景
        RequestContextHolder.resetRequestAttributes();
        assertInstanceOf(WxMockShippingClient.class, dispatcher.shippingClient(CID_JP));
        assertInstanceOf(WxRealShippingClient.class, dispatcher.shippingClient(CID_GUZI));
    }

    @Test
    @DisplayName("向后兼容: 单值配置（apps 未配）→ 任意请求都走那个单一 app")
    void legacyScalarConfig_dispatchesToSingleApp() {
        WxMiniappProperties legacy = new WxMiniappProperties();
        legacy.setAppid("wxMOCK");
        WxAppResolver resolver = new WxAppResolver(legacy);
        WxRealAccessTokenManager realToken = new WxRealAccessTokenManager(resolver);
        WxAdapterDispatcher legacyDispatcher = new WxAdapterDispatcher(
            resolver,
            new WxMockLoginAdapter(resolver),
            new WxRealLoginAdapter(resolver),
            new WxMockPhoneAdapter(),
            new WxRealPhoneAdapter(realToken, resolver),
            new WxMockAccessTokenManager(),
            realToken,
            new WxMockShippingClient(),
            new WxRealShippingClient(realToken, new WxDeliveryListResolver(realToken)));

        givenRequestWithClientId(CID_GUZI);
        assertInstanceOf(WxMockLoginAdapter.class, legacyDispatcher.loginAdapter());
        assertEquals("mock", legacyDispatcher.channel());

        legacy.setAppid(REAL_APPID);
        assertInstanceOf(WxRealLoginAdapter.class, legacyDispatcher.loginAdapter());
        assertEquals("real", legacyDispatcher.channel());
    }
}
