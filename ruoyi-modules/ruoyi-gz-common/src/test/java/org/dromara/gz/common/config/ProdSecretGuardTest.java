package org.dromara.gz.common.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
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
}
