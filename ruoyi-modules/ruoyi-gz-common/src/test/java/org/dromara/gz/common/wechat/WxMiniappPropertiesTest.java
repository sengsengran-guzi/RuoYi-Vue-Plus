package org.dromara.gz.common.wechat;

import org.dromara.common.core.exception.ServiceException;
import org.dromara.gz.common.wechat.WxMiniappProperties.MiniappApp;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link WxMiniappProperties} 单测 —— 多小程序配置解析（GZ-SYS-020 / ADR-0019 §1）。
 *
 * <p>覆盖：</p>
 * <ul>
 *   <li>AC「两个 clientid 各自解析到自己的 appid」：一 real 一 mock 互不干扰</li>
 *   <li>AC「只配旧的单值形态时行为与改造前一致」：标量自动包装成 {默认clientid: 单值}</li>
 *   <li>AC「clientid 未知 → 明确抛错不静默落到默认 app」</li>
 *   <li>dev 开关（mock-phone-fallback / mock-stable-openid）的 app 级覆盖与全局继承</li>
 *   <li>default-client-id 不在 apps 里 → 启动期 fail fast</li>
 * </ul>
 *
 * @author kevin-coder (sensenran-guzi)
 */
@Tag("dev")
class WxMiniappPropertiesTest {

    private static final String CID_GUZI = "mp-applet-sensenran-guzi";
    private static final String CID_JP = "mp-applet-gz-jp";
    private static final String REAL_APPID = "wx2f8b93e09703f07d";

    /** 两 app：旧的 real（真 appid + 真 secret）、新的 mock。 */
    private static WxMiniappProperties twoApps() {
        WxMiniappProperties props = new WxMiniappProperties();
        MiniappApp guzi = new MiniappApp();
        guzi.setAppid(REAL_APPID);
        guzi.setSecret("guzi-secret");
        MiniappApp jp = new MiniappApp();
        jp.setAppid("wxMOCK");
        props.getApps().put(CID_GUZI, guzi);
        props.getApps().put(CID_JP, jp);
        return props;
    }

    @Test
    @DisplayName("AC: 两个 clientid 各自解析到自己的 appid / secret / 通道")
    void resolveApp_twoClients_eachGetsOwnAppid() {
        WxMiniappProperties props = twoApps();

        MiniappApp guzi = props.resolveApp(CID_GUZI);
        assertEquals(REAL_APPID, guzi.getAppid());
        assertEquals("guzi-secret", guzi.getSecret());
        assertEquals(CID_GUZI, guzi.getClientId(), "clientId 应被回填，便于日志定位是哪个小程序");
        assertFalse(guzi.isMock(), "真 appid → real 通道");

        MiniappApp jp = props.resolveApp(CID_JP);
        assertEquals("wxMOCK", jp.getAppid());
        assertTrue(jp.isMock(), "wxMOCK → mock 通道（新小程序还没拿到 secret 时不拖累旧的 real）");

        assertEquals(2, props.resolveApps().size());
    }

    @Test
    @DisplayName("AC: clientid 未知 → 抛错，绝不静默落到默认 app")
    void resolveApp_unknownClientId_throws() {
        WxMiniappProperties props = twoApps();

        ServiceException ex = assertThrows(ServiceException.class,
            () -> props.resolveApp("mp-applet-not-registered"));
        assertTrue(ex.getMessage().contains("mp-applet-not-registered"), "异常信息要带上出问题的 clientid");
        assertTrue(ex.getMessage().contains("wx.miniapp.apps"), "异常信息要指出去哪里登记");
    }

    @Test
    @DisplayName("clientid 缺失（异步 / cron / 单测无 header）→ 落 default-client-id")
    void resolveApp_blankClientId_fallsBackToDefault() {
        WxMiniappProperties props = twoApps();

        assertEquals(REAL_APPID, props.resolveApp(null).getAppid());
        assertEquals(REAL_APPID, props.resolveApp("").getAppid());
        assertEquals(REAL_APPID, props.resolveApp("   ").getAppid());
        assertEquals(REAL_APPID, props.resolveDefaultApp().getAppid());
    }

    @Test
    @DisplayName("向后兼容: 只配单值标量 → 自动包装成 {default-client-id: 单值}")
    void resolveApps_legacyScalarOnly_wrapsIntoDefaultClient() {
        WxMiniappProperties props = new WxMiniappProperties();
        props.setAppid(REAL_APPID);
        props.setSecret("legacy-secret");

        assertEquals(1, props.resolveApps().size());
        assertTrue(props.resolveApps().containsKey(CID_GUZI), "默认 clientid 即 mp 端 VITE_APP_CLIENT_ID");

        MiniappApp app = props.resolveApp(CID_GUZI);
        assertEquals(REAL_APPID, app.getAppid());
        assertEquals("legacy-secret", app.getSecret());
        assertFalse(app.isMock());
        assertFalse(props.isMock(), "isMock() 历史 API 保持语义 = 默认 app 的通道");
    }

    @Test
    @DisplayName("向后兼容: 什么都不配 → 默认 wxMOCK（dev 开箱走 mock，与改造前一致）")
    void resolveApps_noConfig_defaultsToMock() {
        WxMiniappProperties props = new WxMiniappProperties();

        assertEquals(1, props.resolveApps().size());
        assertTrue(props.resolveDefaultApp().isMock());
        assertTrue(props.isMock());
        assertEquals(86400L, props.getSessionKeyTtlSeconds());
        assertEquals(2592000L, props.getTokenTtlSeconds());
    }

    @Test
    @DisplayName("向后兼容: 单值形态下 appid 为空串也视为 mock（历史 isMock 口径）")
    void legacyBlankAppid_isMock() {
        WxMiniappProperties props = new WxMiniappProperties();
        props.setAppid("");
        assertTrue(props.resolveDefaultApp().isMock());
    }

    @Test
    @DisplayName("dev 开关: app 未显式配 → 继承全局标量；显式配 → 覆盖")
    void materialize_devSwitches_inheritOrOverride() {
        WxMiniappProperties props = twoApps();
        props.setMockStableOpenid("dev-tester");
        props.setMockPhoneFallback(true);
        // 新小程序单独配一个稳定 openid，避免两个小程序的 mock 用户看起来是同一人
        props.getApps().get(CID_JP).setMockStableOpenid("jp-tester");
        props.getApps().get(CID_JP).setMockPhoneFallback(false);

        assertEquals("dev-tester", props.resolveApp(CID_GUZI).getMockStableOpenid(), "未配 → 继承全局");
        assertTrue(props.resolveApp(CID_GUZI).isMockPhoneFallbackEnabled());

        assertEquals("jp-tester", props.resolveApp(CID_JP).getMockStableOpenid(), "显式配 → 覆盖全局");
        assertFalse(props.resolveApp(CID_JP).isMockPhoneFallbackEnabled());
    }

    @Test
    @DisplayName("resolveApps() 每次重新物化，不污染绑定对象（改标量立即生效）")
    void resolveApps_isRematerializedEachCall() {
        WxMiniappProperties props = twoApps();
        assertEquals("", props.resolveApp(CID_JP).getMockStableOpenid());

        props.setMockStableOpenid("later-set");
        assertEquals("later-set", props.resolveApp(CID_JP).getMockStableOpenid());
        // 原始绑定对象未被写脏
        assertNull(props.getApps().get(CID_JP).getMockStableOpenid());
    }

    @Test
    @DisplayName("fail fast: default-client-id 不在 apps 里 → 启动期抛错（异步上报会解析不到小程序）")
    void afterPropertiesSet_defaultClientNotInApps_failsFast() {
        WxMiniappProperties props = twoApps();
        props.setDefaultClientId("mp-applet-typo");

        IllegalStateException ex = assertThrows(IllegalStateException.class, props::afterPropertiesSet);
        assertTrue(ex.getMessage().contains("default-client-id"));
    }

    @Test
    @DisplayName("fail fast 不误伤: 单值形态（prod 现状）恒通过启动校验")
    void afterPropertiesSet_legacyScalar_passes() {
        WxMiniappProperties props = new WxMiniappProperties();
        props.setAppid(REAL_APPID);
        props.afterPropertiesSet();

        WxMiniappProperties twoApps = twoApps();
        twoApps.afterPropertiesSet();
    }
}
