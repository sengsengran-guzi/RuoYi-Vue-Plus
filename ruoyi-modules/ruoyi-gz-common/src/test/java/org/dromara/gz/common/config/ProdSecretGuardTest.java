package org.dromara.gz.common.config;

import org.dromara.gz.common.wechat.WxMiniappProperties.MiniappApp;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * prod 敏感配置守卫单测（D16 B3）—— 不启 Spring context，直测 validate。
 */
@Tag("dev")
class ProdSecretGuardTest {

    @Test
    @DisplayName("appid=wxMOCK → 拒绝启动（鉴权静默降级风险）")
    void rejectMockAppid() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> ProdSecretGuard.validate("wxMOCK", "a-real-32+-char-secret-xxxxxxxxxx"));
        assertTrue(ex.getMessage().contains("wx.miniapp.appid"));
    }

    @Test
    @DisplayName("qr secret 仍为占位 → 拒绝启动")
    void rejectPlaceholderQrSecret() {
        assertThrows(IllegalStateException.class,
            () -> ProdSecretGuard.validate("wx_real_appid_123", "CHANGE_ME_BEFORE_PROD_DEPLOY"));
        assertThrows(IllegalStateException.class,
            () -> ProdSecretGuard.validate("wx_real_appid_123", "dev-sensenran-guzi-qr-secret-2026"));
    }

    @Test
    @DisplayName("appid 与 qr secret 都漏配 → 一次列全两项")
    void reportsAllMissing() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> ProdSecretGuard.validate("wxMOCK", null));
        assertTrue(ex.getMessage().contains("wx.miniapp.appid"));
        assertTrue(ex.getMessage().contains("gz.bean.qr.signing-secret"));
    }

    @Test
    @DisplayName("正式值注入 → 通过")
    void passWithRealValues() {
        assertDoesNotThrow(
            () -> ProdSecretGuard.validate("wx9988realappid", "Zk2026-prod-qr-secret-39chars-randomstr!!"));
    }

    @Test
    @DisplayName("pay client-mode 非 real（mock/缺省）→ 拒绝启动（回调零验签资金洞）")
    void rejectNonRealPayClientMode() {
        IllegalStateException mock = assertThrows(IllegalStateException.class,
            () -> ProdSecretGuard.validate("wx9988realappid", "Zk2026-prod-qr-secret-39chars-randomstr!!", "mock"));
        assertTrue(mock.getMessage().contains("gz.pay.client-mode"));
        // 缺省（null = 运维漏注入 WECHAT_PAY_CLIENT_MODE，prod.yml 解析为 mock）同样拒绝
        assertThrows(IllegalStateException.class,
            () -> ProdSecretGuard.validate("wx9988realappid", "Zk2026-prod-qr-secret-39chars-randomstr!!", null));
    }

    @Test
    @DisplayName("三项正式值（含 client-mode=real）→ 通过")
    void passWithRealValuesAndRealPayMode() {
        assertDoesNotThrow(
            () -> ProdSecretGuard.validate("wx9988realappid", "Zk2026-prod-qr-secret-39chars-randomstr!!", "real"));
    }

    @Test
    @DisplayName("多小程序（GZ-SYS-020）: 任一小程序 appid 仍为 wxMOCK → 拒绝启动并点名是哪个 clientid")
    void rejectWhenAnyAppStillMock() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> ProdSecretGuard.validateApps(
                apps("mp-applet-sensenran-guzi", "wx9988realappid", "mp-applet-gz-jp", "wxMOCK"),
                "Zk2026-prod-qr-secret-39chars-randomstr!!", "real"));
        assertTrue(ex.getMessage().contains("mp-applet-gz-jp"), "要点名漏配的是哪个小程序");
        assertFalse(ex.getMessage().contains("[mp-applet-sensenran-guzi]"), "配好的那个不该被点名");
    }

    @Test
    @DisplayName("多小程序（GZ-SYS-020）: 两个小程序都注入正式 appid → 通过")
    void passWhenAllAppsReal() {
        assertDoesNotThrow(() -> ProdSecretGuard.validateApps(
            apps("mp-applet-sensenran-guzi", "wx9988realappid", "mp-applet-gz-jp", "wx7766jpappid"),
            "Zk2026-prod-qr-secret-39chars-randomstr!!", "real"));
    }

    @Test
    @DisplayName("多小程序（GZ-SYS-020）: 一个都没配 → 拒绝启动")
    void rejectWhenNoAppConfigured() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> ProdSecretGuard.validateApps(Map.of(),
                "Zk2026-prod-qr-secret-39chars-randomstr!!", "real"));
        assertTrue(ex.getMessage().contains("未配置任何小程序"));
    }

    private static Map<String, MiniappApp> apps(String cid1, String appid1, String cid2, String appid2) {
        Map<String, MiniappApp> map = new LinkedHashMap<>();
        MiniappApp a1 = new MiniappApp();
        a1.setAppid(appid1);
        MiniappApp a2 = new MiniappApp();
        a2.setAppid(appid2);
        map.put(cid1, a1);
        map.put(cid2, a2);
        return map;
    }
}
