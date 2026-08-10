package org.dromara.gz.common.config;

import org.dromara.gz.common.wechat.WxMiniappProperties;
import org.dromara.gz.common.wechat.WxMiniappProperties.MiniappApp;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 临时验证：真的 application-prod.yml（不是手写的 property list）在 prod / prod+jp 两种形态下
 * 各解析出什么。跑完即删。
 */
@Tag("dev")
class WxMiniappProdProfileVerifyTest {

    @Configuration
    static class Bare {
    }

    private WxMiniappProperties load(String... props) {
        return loadFile("application-prod.yml", props);
    }

    private WxMiniappProperties loadFile(String file, String... props) {
        String[] all = new String[props.length + 2];
        all[0] = "spring.config.location=classpath:/" + file;
        all[1] = "spring.main.banner-mode=off";
        System.arraycopy(props, 0, all, 2, props.length);
        try (ConfigurableApplicationContext ctx = new SpringApplicationBuilder(Bare.class)
            .web(WebApplicationType.NONE)
            .properties(all)
            .run()) {
            WxMiniappProperties p = Binder.get(ctx.getEnvironment())
                .bind("wx.miniapp", WxMiniappProperties.class)
                .orElseGet(WxMiniappProperties::new);
            p.afterPropertiesSet();
            System.out.println("### 激活 profile = " + String.join(",", ctx.getEnvironment().getActiveProfiles()));
            p.resolveApps().forEach((cid, app) -> System.out.printf(
                "### clientid=%-28s appid=%-22s channel=%-5s registerSource=%s%n",
                cid, "[" + app.getAppid() + "]", app.isMock() ? "mock" : "real", app.getRegisterSource()));
            return p;
        }
    }

    @Test
    @DisplayName("A. 只有 prod（拼团 appid 还没到手）→ 只注册现小程序，拼团 clientid 明确拒绝，守卫放行")
    void prodOnly() {
        WxMiniappProperties p = load("spring.profiles.active=prod", "spring.profiles.include=");
        Map<String, MiniappApp> apps = p.resolveApps();
        assertEquals(1, apps.size());
        assertEquals("wx2f8b93e09703f07d", apps.get("mp-applet-sensenran-guzi").getAppid());
        assertFalse(apps.containsKey("mp-applet-gz-jp"));
        System.out.println("### 拼团 clientid 请求 → " + assertThrows(RuntimeException.class,
            () -> p.resolveApp("mp-applet-gz-jp")).getMessage());
        // 现小程序真凭证齐全 → 守卫放行（这就是「今天部署不会被这次改动搞挂」的证据）
        ProdSecretGuard.validateApps(apps, "Zk2026-prod-qr-secret-39chars-randomstr!!", "real");
        System.out.println("### ProdSecretGuard: 放行");
    }

    @Test
    @DisplayName("B. prod + jp + 两套 env → 两个 clientid 各自解析出自己的 appid")
    void bothAppsResolveOwnAppid() {
        WxMiniappProperties p = load(
            "spring.profiles.active=prod",
            "spring.profiles.include=jp",
            "WX_MA_APPID=wx2f8b93e09703f07d",
            "WX_MA_SECRET=guzi-real-secret",
            "WX_JP_MA_APPID=wxJPREALAPPID0001",
            "WX_JP_MA_SECRET=jp-real-secret");
        Map<String, MiniappApp> apps = p.resolveApps();
        assertEquals(2, apps.size(), "两个小程序必须同时在册（Map 跨 document 合并）");

        MiniappApp guzi = apps.get("mp-applet-sensenran-guzi");
        assertEquals("wx2f8b93e09703f07d", guzi.getAppid());
        assertEquals("guzi-real-secret", guzi.getSecret());
        assertFalse(guzi.isMock());
        assertEquals("mp_wechat", guzi.getRegisterSource());

        MiniappApp jp = apps.get("mp-applet-gz-jp");
        assertEquals("wxJPREALAPPID0001", jp.getAppid());
        assertEquals("jp-real-secret", jp.getSecret());
        assertFalse(jp.isMock());
        assertEquals("mp_wechat_jp", jp.getRegisterSource());

        assertFalse(guzi.getAppid().equals(jp.getAppid()), "两个 appid 不能串");
        ProdSecretGuard.validateApps(apps, "Zk2026-prod-qr-secret-39chars-randomstr!!", "real");
        System.out.println("### ProdSecretGuard: 放行（2 个 app 都是真 appid）");
    }

    @Test
    @DisplayName("C. prod + jp 但漏了 WX_JP_MA_APPID → 守卫点名 mp-applet-gz-jp 拒绝启动")
    void jpProfileWithoutAppidIsNamedByGuard() {
        WxMiniappProperties p = load(
            "spring.profiles.active=prod",
            "spring.profiles.include=jp",
            "WX_MA_APPID=wx2f8b93e09703f07d",
            "WX_MA_SECRET=guzi-real-secret");
        Map<String, MiniappApp> apps = p.resolveApps();
        assertEquals(2, apps.size());
        assertTrue(apps.get("mp-applet-gz-jp").isMock(), "漏配 → 该 app 会走 mock 通道（正是守卫要防的）");

        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> ProdSecretGuard.validateApps(apps, "Zk2026-prod-qr-secret-39chars-randomstr!!", "real"));
        System.out.println("### ProdSecretGuard 拒绝启动：\n" + ex.getMessage());
        assertTrue(ex.getMessage().contains("mp-applet-gz-jp"), "错误信息必须点名拼团 clientid");
        assertFalse(ex.getMessage().contains("mp-applet-sensenran-guzi"), "现小程序凭证齐全，不该被点名");
    }

    @Test
    @DisplayName("E. dev 配置未受影响：什么都不配，两个小程序都在册且都走 mock，无守卫")
    void devProfileUntouched() {
        WxMiniappProperties p = loadFile("application-dev.yml", "spring.profiles.active=dev");
        Map<String, MiniappApp> apps = p.resolveApps();
        assertEquals(2, apps.size());
        assertTrue(apps.get("mp-applet-sensenran-guzi").isMock());
        assertTrue(apps.get("mp-applet-gz-jp").isMock());
        assertEquals("mp_wechat_jp", apps.get("mp-applet-gz-jp").getRegisterSource());
    }

    @Test
    @DisplayName("D. 真 OS 环境变量链路（模拟 compose 透传）：SPRING_PROFILES_INCLUDE + WX_JP_MA_APPID")
    @EnabledIfEnvironmentVariable(named = "WX_JP_MA_APPID", matches = ".+")
    void realOsEnvVars() {
        WxMiniappProperties p = load("spring.profiles.active=prod");
        Map<String, MiniappApp> apps = p.resolveApps();
        System.out.println("### OS env WX_JP_MA_APPID=" + System.getenv("WX_JP_MA_APPID")
            + " SPRING_PROFILES_INCLUDE=" + System.getenv("SPRING_PROFILES_INCLUDE"));
        assertEquals(2, apps.size());
        assertEquals(System.getenv("WX_JP_MA_APPID"), apps.get("mp-applet-gz-jp").getAppid());
        assertEquals(System.getenv("WX_JP_MA_SECRET"), apps.get("mp-applet-gz-jp").getSecret());
        assertEquals(System.getenv("WX_MA_APPID"), apps.get("mp-applet-sensenran-guzi").getAppid());
    }
}
