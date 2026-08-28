package org.dromara.gz.common.config;

import org.dromara.common.core.domain.R;
import org.dromara.common.core.service.ConfigService;
import org.dromara.gz.common.controller.applet.GzConfigMpController;
import org.dromara.gz.common.wechat.WxAppResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.dromara.gz.common.config.MpPublicConfigKeyResolver.CLIENT_ID_GUZI;
import static org.dromara.gz.common.config.MpPublicConfigKeyResolver.CLIENT_ID_JP;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * GZ-JP-201 mp 公开配置 key 按小程序分身单测。
 *
 * <p>守两条底线：① 谷子宇宙（线上）读到的 key 一字不变；② 拼团读不到谷子宇宙那份（反之亦然）。</p>
 */
@Tag("dev")
class MpPublicConfigKeyResolverTest {

    private MpPublicConfigKeyResolver resolverFor(String clientId) {
        WxAppResolver appResolver = mock(WxAppResolver.class);
        when(appResolver.currentClientId()).thenReturn(clientId);
        return new MpPublicConfigKeyResolver(appResolver);
    }

    @Test
    @DisplayName("谷子宇宙小程序：首页 banner key 保持历史值 gz.home.banners（线上零变化）")
    void guzi_home_banners_key_unchanged() {
        assertEquals("gz.home.banners", resolverFor(CLIENT_ID_GUZI).resolve("gz.home.banners"));
    }

    @Test
    @DisplayName("拼团小程序：首页 banner key 归一到 gz.jp.home.banners（不再串味）")
    void jp_home_banners_key_isolated() {
        assertEquals("gz.jp.home.banners", resolverFor(CLIENT_ID_JP).resolve("gz.jp.home.banners"));
    }

    @Test
    @DisplayName("拼团误传谷子宇宙的 key（页面拷贝漏改常量）→ 仍归一到自己那份，自愈不串味")
    void jp_requesting_guzi_key_is_normalized() {
        assertEquals("gz.jp.home.banners", resolverFor(CLIENT_ID_JP).resolve("gz.home.banners"));
    }

    @Test
    @DisplayName("谷子宇宙误传拼团 key → 归一回自己那份，读不到拼团素材")
    void guzi_requesting_jp_key_is_normalized() {
        assertEquals("gz.home.banners", resolverFor(CLIENT_ID_GUZI).resolve("gz.jp.home.banners"));
    }

    @Test
    @DisplayName("无 clientid 上下文（curl / cron，WxAppResolver 回落默认）→ 默认小程序那份")
    void fallback_client_gets_default_key() {
        assertEquals("gz.home.banners", resolverFor(CLIENT_ID_GUZI).resolve("gz.home.banners"));
        // plus-ui 后台 PC clientid 也走同一路径：不是已登记小程序 → 落默认 key（改造前行为）
        assertEquals("gz.home.banners", resolverFor("e5cd7e4891bf95d1d19206ce24a7b32e").resolve("gz.home.banners"));
    }

    @Test
    @DisplayName("拼豆落地页 banner 无分身：两个小程序都解析到同一个 key")
    void bean_home_banner_has_no_variant() {
        assertEquals("gz.bean.home.banner", resolverFor(CLIENT_ID_GUZI).resolve("gz.bean.home.banner"));
        assertEquals("gz.bean.home.banner", resolverFor(CLIENT_ID_JP).resolve("gz.bean.home.banner"));
    }

    @Test
    @DisplayName("回收客服二维码无分身：两个小程序都解析到同一个 key")
    void recycle_service_qrcode_has_no_variant() {
        assertEquals("gz.recycle.serviceQrcode", resolverFor(CLIENT_ID_GUZI).resolve("gz.recycle.serviceQrcode"));
        assertEquals("gz.recycle.serviceQrcode", resolverFor(CLIENT_ID_JP).resolve("gz.recycle.serviceQrcode"));
    }

    @Test
    @DisplayName("非白名单 key（分成率 / 默认密码）→ null，controller 据此拒绝")
    void non_whitelisted_key_rejected() {
        MpPublicConfigKeyResolver r = resolverFor(CLIENT_ID_GUZI);
        assertNull(r.resolve("gz.commission.rate.preorder"));
        assertNull(r.resolve("sys.user.initPassword"));
        assertNull(r.resolve(""));
    }

    @Test
    @DisplayName("白名单登记表含两个小程序各自的 banner key")
    void all_public_keys_contains_both_banner_keys() {
        assertTrue(MpPublicConfigKeyResolver.allPublicKeys().contains("gz.home.banners"));
        assertTrue(MpPublicConfigKeyResolver.allPublicKeys().contains("gz.jp.home.banners"));
        assertTrue(MpPublicConfigKeyResolver.allPublicKeys().contains("gz.bean.home.banner"));
    }

    @Test
    @DisplayName("controller 端到端：同一请求 key，两个小程序读到各自 config_value")
    void controller_reads_per_app_value() {
        ConfigService configService = mock(ConfigService.class);
        when(configService.getConfigValue("gz.home.banners")).thenReturn("[{\"imageUrl\":\"guzi.png\"}]");
        when(configService.getConfigValue("gz.jp.home.banners")).thenReturn("[{\"imageUrl\":\"jp.png\"}]");

        R<String> guzi = new GzConfigMpController(configService, resolverFor(CLIENT_ID_GUZI))
            .get("gz.home.banners");
        R<String> jp = new GzConfigMpController(configService, resolverFor(CLIENT_ID_JP))
            .get("gz.jp.home.banners");

        assertEquals(200, guzi.getCode());
        assertEquals("[{\"imageUrl\":\"guzi.png\"}]", guzi.getData());
        assertEquals(200, jp.getCode());
        assertEquals("[{\"imageUrl\":\"jp.png\"}]", jp.getData());
    }

    @Test
    @DisplayName("controller 拒绝非白名单 key：不落到 ConfigService")
    void controller_rejects_non_whitelisted() {
        ConfigService configService = mock(ConfigService.class);
        R<String> r = new GzConfigMpController(configService, resolverFor(CLIENT_ID_GUZI))
            .get("gz.commission.rate.preorder");
        assertEquals(500, r.getCode());
        assertNull(r.getData());
    }
}
