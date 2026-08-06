package org.dromara.gz.common.wechat;

import cn.hutool.extra.spring.SpringUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.http.HttpUtil;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.redis.utils.RedisUtils;
import org.dromara.gz.common.wechat.WxMiniappProperties.MiniappApp;
import org.dromara.gz.common.wechat.impl.WxRealAccessTokenManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.redisson.api.RedissonClient;
import org.springframework.context.ApplicationContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * {@link WxRealAccessTokenManager} access_token 缓存<b>按 appid 隔离</b>单测（GZ-SYS-021 / ADR-0019 §2）。
 *
 * <p>微信 {@code cgi-bin/token} 返回的 access_token 是 <b>appid 维度</b>凭证：两个小程序的 token 值不同、
 * 互不通用。改造前缓存 key 是不带 appid 的固定串 {@code wx:miniapp:access_token} —— 两个 real 小程序共用
 * 一个 Redis 槽位，后刷新者覆盖先刷新者，手机号解密 / 发货上报会<b>间歇性</b>拿到隔壁小程序的 token 返
 * 40001 invalid credential。本测试锁死新口径：key = {@code wx:miniapp:access_token:{appid}}，
 * 读 / 写 / 强刷三条路径都按各自 appid 走，互不覆盖。</p>
 *
 * <p><b>期望 key 在本测试里是手写字面量</b>（不引用生产常量）—— 这样它是一份从外部锁死的契约：
 * 生产侧改了 key 形态、或漏了那个分隔冒号，测试立刻红。</p>
 *
 * <p>不触网、不连 Redis：{@code RedisUtils} 用 Map 假实现替身（同时记录读过哪些 key），
 * {@code HttpUtil} 静态 mock 掉（多数用例干脆让它一触网就失败，用来证明「本该命中缓存」）。</p>
 *
 * @author kevin-coder (sensenran-guzi)
 */
@Tag("dev")
class WxAccessTokenIsolationTest {

    private static final String CID_GUZI = "mp-applet-sensenran-guzi";
    private static final String CID_JP = "mp-applet-gz-jp";

    /** 现小程序（谷子宇宙）真 appid —— application-prod.yml 默认值。 */
    private static final String APPID_GUZI = "wx2f8b93e09703f07d";
    /** 日本拼团小程序 appid（两个都必须是 real 才谈得上「共用槽位互相覆盖」）。 */
    private static final String APPID_JP = "wx77c1c88d2b0f1a2c";

    /** ★ 契约：完整 key = 前缀 + appid。手写字面量，不引生产常量。 */
    private static final String KEY_GUZI = "wx:miniapp:access_token:" + APPID_GUZI;
    private static final String KEY_JP = "wx:miniapp:access_token:" + APPID_JP;

    /** plus-ui 后台的 PC 认证客户端 id —— 不是小程序，但后台「发货信息手动补报」带着它进来。 */
    private static final String ADMIN_PC_CLIENT_ID = "e5cd7e4891bf95d1d19206ce24a7b32e";

    private static final String CLIENT_HEADER = "clientid";

    /** Redis 假实现：key → value。 */
    private final Map<String, Object> redisStore = new LinkedHashMap<>();
    /** Redis 假实现：key → TTL（断言提前量语义用）。 */
    private final Map<String, Duration> redisTtl = new LinkedHashMap<>();
    /** 按顺序记录读过的 key（断言「读的是自己那把 key」）。 */
    private final List<String> redisReads = new ArrayList<>();
    /** 记录真正发给微信的表单参数（断言「用的是本 app 自己的凭证」）。 */
    private final Map<String, Object> wechatForm = new LinkedHashMap<>();

    private WxMiniappProperties properties;
    private WxRealAccessTokenManager manager;

    /** 进场前 SpringUtil 里挂的上下文（若有），退场还回去，避免污染同一 fork 里的其他测试类。 */
    private static ApplicationContext previousSpringContext;

    /**
     * 让 {@code RedisUtils} 的静态初始化能跑完。
     *
     * <p>{@code RedisUtils} 有一句 {@code static final RedissonClient CLIENT = SpringUtils.getBean(...)}，
     * 无 Spring 环境下直接 {@code ExceptionInInitializerError} —— 表现是 {@code mockStatic(RedisUtils.class)}
     * 报「Cannot instrument class RedisUtils because it or one of its supertypes could not be initialized」。
     * 先塞一个假 ApplicationContext 让它初始化完成即可；{@code CLIENT} 本身用不到（所有 RedisUtils 调用
     * 在用例里都被静态 mock 拦掉，一次真 Redis 都不会打）。</p>
     */
    @BeforeAll
    static void bootstrapSpringUtilSoRedisUtilsCanInitialize() {
        previousSpringContext = SpringUtil.getApplicationContext();
        ApplicationContext ctx = mock(ApplicationContext.class);
        when(ctx.getBean(RedissonClient.class)).thenReturn(mock(RedissonClient.class));
        new SpringUtil().setApplicationContext(ctx);
    }

    @AfterAll
    static void restoreSpringUtil() {
        if (previousSpringContext != null) {
            new SpringUtil().setApplicationContext(previousSpringContext);
        }
    }

    @BeforeEach
    void setUp() {
        properties = new WxMiniappProperties();
        MiniappApp guzi = new MiniappApp();
        guzi.setAppid(APPID_GUZI);
        guzi.setSecret("guzi-secret");
        MiniappApp jp = new MiniappApp();
        jp.setAppid(APPID_JP);
        jp.setSecret("jp-secret");
        properties.getApps().put(CID_GUZI, guzi);
        properties.getApps().put(CID_JP, jp);
        properties.setDefaultClientId(CID_GUZI);

        manager = new WxRealAccessTokenManager(new WxAppResolver(properties));
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

    /** Map 假实现替身：记录读 key、写值与 TTL。 */
    private MockedStatic<RedisUtils> givenFakeRedis() {
        MockedStatic<RedisUtils> redis = mockStatic(RedisUtils.class);
        redis.when(() -> RedisUtils.getCacheObject(anyString())).thenAnswer(inv -> {
            String key = inv.getArgument(0);
            redisReads.add(key);
            return redisStore.get(key);
        });
        redis.when(() -> RedisUtils.setCacheObject(anyString(), any(), any(Duration.class))).thenAnswer(inv -> {
            redisStore.put(inv.getArgument(0), inv.getArgument(1));
            redisTtl.put(inv.getArgument(0), inv.getArgument(2));
            return null;
        });
        return redis;
    }

    /** 微信 cgi-bin/token 假响应（不触网）。 */
    private MockedStatic<HttpUtil> givenWechatTokenApi(String responseBody) {
        MockedStatic<HttpUtil> http = mockStatic(HttpUtil.class);
        HttpRequest request = mock(HttpRequest.class);
        HttpResponse response = mock(HttpResponse.class);
        http.when(() -> HttpUtil.createGet(anyString())).thenReturn(request);
        when(request.form(anyMap())).thenAnswer(inv -> {
            wechatForm.putAll(inv.<Map<String, Object>>getArgument(0));
            return request;
        });
        when(request.timeout(anyInt())).thenReturn(request);
        when(request.execute()).thenReturn(response);
        when(response.body()).thenReturn(responseBody);
        return http;
    }

    /** 一触网就失败 —— 用于「本该命中缓存」的用例：key 少了 appid 维度就会 miss 并去刷新，这里立刻炸。 */
    private MockedStatic<HttpUtil> givenNetworkIsForbidden() {
        MockedStatic<HttpUtil> http = mockStatic(HttpUtil.class);
        http.when(() -> HttpUtil.createGet(anyString()))
            .thenThrow(new AssertionError("本用例不应触网：缓存该命中却发起了 cgi-bin/token 刷新"));
        return http;
    }

    @Test
    @DisplayName("★AC: 两个 real 小程序各读各的缓存槽（key 带 appid），互不串号")
    void twoRealApps_readTheirOwnCacheSlot() {
        redisStore.put(KEY_GUZI, "token-of-guzi");
        redisStore.put(KEY_JP, "token-of-jp");

        try (MockedStatic<RedisUtils> redis = givenFakeRedis();
             MockedStatic<HttpUtil> http = givenNetworkIsForbidden()) {

            givenRequestWithClientId(CID_GUZI);
            assertEquals("token-of-guzi", manager.getToken(false));

            givenRequestWithClientId(CID_JP);
            assertEquals("token-of-jp", manager.getToken(false));
        }

        // ★ 契约：读的必须是「前缀 + 自己的 appid」这两把 key，一把都不能错
        assertEquals(List.of(KEY_GUZI, KEY_JP), redisReads);
    }

    @Test
    @DisplayName("★AC: 刷新只写自己那把 key，绝不覆盖另一个小程序的 token")
    void refresh_writesOnlyItsOwnSlot() {
        // 隔壁小程序先刷好了 token 躺在缓存里
        redisStore.put(KEY_JP, "token-of-jp-must-survive");
        redisTtl.put(KEY_JP, Duration.ofSeconds(6900));

        try (MockedStatic<RedisUtils> redis = givenFakeRedis();
             MockedStatic<HttpUtil> http = givenWechatTokenApi(
                 "{\"access_token\":\"fresh-guzi-token\",\"expires_in\":7200}")) {

            givenRequestWithClientId(CID_GUZI);
            assertEquals("fresh-guzi-token", manager.getToken(false));
        }

        assertEquals("fresh-guzi-token", redisStore.get(KEY_GUZI));
        // ★ 改造前这里会被覆盖成 fresh-guzi-token → 拼团小程序拿着谷子宇宙的 token 去解密手机号 → 40001
        assertEquals("token-of-jp-must-survive", redisStore.get(KEY_JP));
        assertEquals(2, redisStore.size(), "只该多出自己那一把 key，实际写了：" + redisStore.keySet());
        // 刷新用的必须是本 app 自己的凭证（否则槽里的 token 与 key 上的 appid 对不上，等于换个姿势串号）
        assertEquals("client_credential", wechatForm.get("grant_type"));
        assertEquals(APPID_GUZI, wechatForm.get("appid"));
        assertEquals("guzi-secret", wechatForm.get("secret"));
    }

    @Test
    @DisplayName("AC: 刷新 TTL 保持 expires_in - 300s 提前量（语义不变）")
    void refresh_keepsExpirySafetyMargin() {
        try (MockedStatic<RedisUtils> redis = givenFakeRedis();
             MockedStatic<HttpUtil> http = givenWechatTokenApi(
                 "{\"access_token\":\"t\",\"expires_in\":7200}")) {

            givenRequestWithClientId(CID_JP);
            manager.getToken(false);
        }

        assertEquals(Duration.ofSeconds(6900), redisTtl.get(KEY_JP));
    }

    @Test
    @DisplayName("AC: expires_in 过短时 TTL 兜底 60s（语义不变）")
    void refresh_ttlFloorsAt60Seconds() {
        try (MockedStatic<RedisUtils> redis = givenFakeRedis();
             MockedStatic<HttpUtil> http = givenWechatTokenApi(
                 "{\"access_token\":\"t\",\"expires_in\":100}")) {

            givenRequestWithClientId(CID_JP);
            manager.getToken(false);
        }

        assertEquals(Duration.ofSeconds(60), redisTtl.get(KEY_JP));
    }

    @Test
    @DisplayName("AC: forceRefresh 跳过读缓存，且强刷只替换自己那把 key（失效重取路径也按 appid 走）")
    void forceRefresh_skipsCacheAndReplacesOnlyItsOwnSlot() {
        redisStore.put(KEY_GUZI, "stale-guzi-token");
        redisStore.put(KEY_JP, "token-of-jp-must-survive");

        try (MockedStatic<RedisUtils> redis = givenFakeRedis();
             MockedStatic<HttpUtil> http = givenWechatTokenApi(
                 "{\"access_token\":\"refreshed-guzi-token\",\"expires_in\":7200}")) {

            givenRequestWithClientId(CID_GUZI);
            assertEquals("refreshed-guzi-token", manager.getToken(true));
        }

        assertTrue(redisReads.isEmpty(), "forceRefresh 不该读缓存，实际读了：" + redisReads);
        assertEquals("refreshed-guzi-token", redisStore.get(KEY_GUZI));
        assertEquals("token-of-jp-must-survive", redisStore.get(KEY_JP));
    }

    @Test
    @DisplayName("AC: 无请求上下文（@Async 发货上报 / cron）→ 用 default-client-id 那个小程序的槽")
    void noRequestContext_usesDefaultAppSlot() {
        RequestContextHolder.resetRequestAttributes();
        redisStore.put(KEY_GUZI, "token-of-guzi");
        redisStore.put(KEY_JP, "token-of-jp");

        try (MockedStatic<RedisUtils> redis = givenFakeRedis();
             MockedStatic<HttpUtil> http = givenNetworkIsForbidden()) {

            assertEquals("token-of-guzi", manager.getToken(false));

            // 默认小程序换成拼团那个 → 同一个 manager 立刻改读拼团的槽（不是启动期定死）
            properties.setDefaultClientId(CID_JP);
            assertEquals("token-of-jp", manager.getToken(false));
        }

        assertEquals(List.of(KEY_GUZI, KEY_JP), redisReads);
    }

    @Test
    @DisplayName("★回归: 后台 admin 的 PC clientid（发货手动补报）→ 落默认 app 的槽，不抛错")
    void adminPcClientId_fallsBackToDefaultAppSlot() {
        // GZ-SYS-020 §6：access_token 是共享能力，走宽松解析；用严格口径会把后台补报按钮打挂
        givenRequestWithClientId(ADMIN_PC_CLIENT_ID);
        redisStore.put(KEY_GUZI, "token-of-guzi");

        try (MockedStatic<RedisUtils> redis = givenFakeRedis();
             MockedStatic<HttpUtil> http = givenNetworkIsForbidden()) {

            assertEquals("token-of-guzi", manager.getToken(false));
        }

        assertEquals(List.of(KEY_GUZI), redisReads);
    }

    @Test
    @DisplayName("AC: 刷新失败（微信返 errcode）→ 抛错且一把 key 都不写（失败不污染任何槽）")
    void refreshFailure_writesNothing() {
        redisStore.put(KEY_JP, "token-of-jp-must-survive");

        try (MockedStatic<RedisUtils> redis = givenFakeRedis();
             MockedStatic<HttpUtil> http = givenWechatTokenApi(
                 "{\"errcode\":40125,\"errmsg\":\"invalid appsecret\"}")) {

            givenRequestWithClientId(CID_GUZI);
            ServiceException ex = assertThrows(ServiceException.class, () -> manager.getToken(false));
            assertTrue(ex.getMessage().contains("invalid appsecret"));
        }

        assertEquals(1, redisStore.size());
        assertEquals("token-of-jp-must-survive", redisStore.get(KEY_JP));
    }

    @Test
    @DisplayName("向后兼容: 单值配置（apps 未配）→ key 仍按那个唯一 appid 拼，不退化成无 appid 的全局串")
    void legacyScalarConfig_stillScopesKeyByAppid() {
        WxMiniappProperties legacy = new WxMiniappProperties();
        legacy.setAppid(APPID_GUZI);
        legacy.setSecret("guzi-secret");
        WxRealAccessTokenManager legacyManager = new WxRealAccessTokenManager(new WxAppResolver(legacy));
        redisStore.put(KEY_GUZI, "token-of-guzi");

        try (MockedStatic<RedisUtils> redis = givenFakeRedis();
             MockedStatic<HttpUtil> http = givenNetworkIsForbidden()) {

            givenRequestWithClientId(CID_GUZI);
            assertEquals("token-of-guzi", legacyManager.getToken(false));
        }

        assertEquals(List.of(KEY_GUZI), redisReads);
    }
}
