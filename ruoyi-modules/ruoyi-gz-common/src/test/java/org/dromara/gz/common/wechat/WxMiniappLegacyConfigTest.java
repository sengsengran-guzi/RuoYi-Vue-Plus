package org.dromara.gz.common.wechat;

import org.dromara.gz.common.pay.shipping.WxShippingClient;
import org.dromara.gz.common.pay.shipping.impl.WxMockShippingClient;
import org.dromara.gz.common.pay.shipping.impl.WxRealShippingClient;
import org.dromara.gz.common.wechat.impl.WxMockAccessTokenManager;
import org.dromara.gz.common.wechat.impl.WxMockLoginAdapter;
import org.dromara.gz.common.wechat.impl.WxMockPhoneAdapter;
import org.dromara.gz.common.wechat.impl.WxRealAccessTokenManager;
import org.dromara.gz.common.wechat.impl.WxRealLoginAdapter;
import org.dromara.gz.common.wechat.impl.WxRealPhoneAdapter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 向后兼容验收 —— <b>旧单值配置不改，服务照常启动且行为与改造前一致</b>（GZ-SYS-020 AC 2）。
 *
 * <p>这是本次改造最要命的一条：改的是线上正在跑的登录 / 支付底层。用
 * {@link ApplicationContextRunner} 真起一个只含微信装配的最小容器，验证：</p>
 * <ul>
 *   <li>prod 现状（{@code wx.miniapp.appid}=真 AppID + {@code secret}）→ 容器起得来、通道仍是 real</li>
 *   <li>dev 现状（{@code appid=wxMOCK} + {@code mock-phone-fallback} / {@code mock-stable-openid} 标量）
 *       → 通道仍是 mock，两个 dev 开关仍生效</li>
 *   <li>8 个 real/mock 实现<b>同时</b>在容器里（条件 Bean 已撤），{@code @Primary} 门面唯一可注入</li>
 *   <li>新形态：{@code wx.miniapp.apps.<clientid>.*} 能被 Spring 松散绑定正确解析（含 key 里的连字符）</li>
 *   <li>{@code default-client-id} 配错 → 启动即失败（而不是等异步发货上报时才炸）</li>
 * </ul>
 *
 * @author kevin-coder (sensenran-guzi)
 */
@Tag("dev")
class WxMiniappLegacyConfigTest {

    private static final String CID_GUZI = "mp-applet-sensenran-guzi";
    private static final String CID_JP = "mp-applet-gz-jp";
    private static final String REAL_APPID = "wx2f8b93e09703f07d";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(WxTestConfig.class);

    @Test
    @DisplayName("AC 2: prod 旧单值配置（真 appid + secret）不改 → 启动成功且仍走 real")
    void legacyProdScalarConfig_startsAndStaysReal() {
        runner.withPropertyValues(
                "wx.miniapp.appid=" + REAL_APPID,
                "wx.miniapp.secret=prod-secret",
                "wx.miniapp.session-key-ttl-seconds=86400",
                "wx.miniapp.token-ttl-seconds=2592000")
            .run(context -> {
                assertThat(context).hasNotFailed();

                WxMiniappProperties props = context.getBean(WxMiniappProperties.class);
                assertThat(props.resolveApps()).hasSize(1).containsKey(CID_GUZI);
                assertThat(props.resolveDefaultApp().getAppid()).isEqualTo(REAL_APPID);
                assertThat(props.resolveDefaultApp().getSecret()).isEqualTo("prod-secret");
                assertThat(props.isMock()).isFalse();
                assertThat(props.getTokenTtlSeconds()).isEqualTo(2592000L);

                WxAdapterDispatcher dispatcher = context.getBean(WxAdapterDispatcher.class);
                assertThat(dispatcher.channel()).isEqualTo("real");
                assertThat(dispatcher.loginAdapter()).isInstanceOf(WxRealLoginAdapter.class);
                assertThat(dispatcher.phoneAdapter()).isInstanceOf(WxRealPhoneAdapter.class);
                assertThat(dispatcher.accessTokenManager()).isInstanceOf(WxRealAccessTokenManager.class);
                assertThat(dispatcher.shippingClient()).isInstanceOf(WxRealShippingClient.class);
            });
    }

    @Test
    @DisplayName("AC 2: dev 旧单值配置（wxMOCK + 两个 dev 开关）不改 → 仍走 mock，开关仍生效")
    void legacyDevScalarConfig_startsAndStaysMock() {
        runner.withPropertyValues(
                "wx.miniapp.appid=wxMOCK",
                "wx.miniapp.secret=",
                "wx.miniapp.mock-phone-fallback=true",
                "wx.miniapp.mock-stable-openid=dev-tester")
            .run(context -> {
                assertThat(context).hasNotFailed();

                WxMiniappProperties props = context.getBean(WxMiniappProperties.class);
                assertThat(props.isMock()).isTrue();
                assertThat(props.resolveDefaultApp().isMockPhoneFallbackEnabled()).isTrue();
                assertThat(props.resolveDefaultApp().getMockStableOpenid()).isEqualTo("dev-tester");

                WxAdapterDispatcher dispatcher = context.getBean(WxAdapterDispatcher.class);
                assertThat(dispatcher.channel()).isEqualTo("mock");
                assertThat(dispatcher.loginAdapter()).isInstanceOf(WxMockLoginAdapter.class);
                // dev 稳定 mock 用户口径不变（换 code 仍同一 openid）
                assertThat(dispatcher.code2Session("code-AAAA1111").getOpenid()).isEqualTo("mock-dev-tester");
                assertThat(dispatcher.code2Session("code-BBBB2222").getOpenid()).isEqualTo("mock-dev-tester");
                assertThat(dispatcher.code2Phone("any")).isEqualTo("13800000000");
            });
    }

    @Test
    @DisplayName("AC 2: 完全不配 wx.miniapp → 默认 wxMOCK 起得来（改造前同样行为）")
    void noConfigAtAll_startsWithMockDefaults() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(WxAdapterDispatcher.class).channel()).isEqualTo("mock");
        });
    }

    @Test
    @DisplayName("条件 Bean 已撤：8 个 real/mock 实现同时在容器里，接口注入点由 @Primary 门面唯一裁决")
    void bothRealAndMockBeansRegistered_primaryFacadeWins() {
        runner.withPropertyValues("wx.miniapp.appid=" + REAL_APPID).run(context -> {
            assertThat(context).hasSingleBean(WxRealLoginAdapter.class);
            assertThat(context).hasSingleBean(WxMockLoginAdapter.class);
            assertThat(context).hasSingleBean(WxRealPhoneAdapter.class);
            assertThat(context).hasSingleBean(WxMockPhoneAdapter.class);
            assertThat(context).hasSingleBean(WxRealAccessTokenManager.class);
            assertThat(context).hasSingleBean(WxMockAccessTokenManager.class);
            assertThat(context).hasSingleBean(WxRealShippingClient.class);
            assertThat(context).hasSingleBean(WxMockShippingClient.class);

            // 业务侧按接口注入 → 拿到的是 @Primary 门面（不会 NoUniqueBeanDefinitionException）
            assertThat(context.getBean(WxLoginAdapter.class)).isInstanceOf(WxAdapterDispatcher.class);
            assertThat(context.getBean(WxPhoneAdapter.class)).isInstanceOf(WxAdapterDispatcher.class);
            assertThat(context.getBean(WxAccessTokenManager.class)).isInstanceOf(WxAdapterDispatcher.class);
            assertThat(context.getBean(WxShippingClient.class)).isInstanceOf(WxAdapterDispatcher.class);
        });
    }

    @Test
    @DisplayName("新形态: wx.miniapp.apps.<clientid> 松散绑定可用（key 含连字符），两 app 一 real 一 mock")
    void mapForm_bindsDashedClientIdKeys() {
        runner.withPropertyValues(
                "wx.miniapp.default-client-id=" + CID_GUZI,
                "wx.miniapp.apps." + CID_GUZI + ".appid=" + REAL_APPID,
                "wx.miniapp.apps." + CID_GUZI + ".secret=guzi-secret",
                "wx.miniapp.apps." + CID_JP + ".appid=wxMOCK",
                "wx.miniapp.apps." + CID_JP + ".mock-stable-openid=jp-tester")
            .run(context -> {
                assertThat(context).hasNotFailed();

                WxMiniappProperties props = context.getBean(WxMiniappProperties.class);
                assertThat(props.resolveApps()).containsOnlyKeys(CID_GUZI, CID_JP);
                assertThat(props.resolveApp(CID_GUZI).getAppid()).isEqualTo(REAL_APPID);
                assertThat(props.resolveApp(CID_GUZI).getSecret()).isEqualTo("guzi-secret");
                assertThat(props.resolveApp(CID_GUZI).isMock()).isFalse();
                assertThat(props.resolveApp(CID_JP).isMock()).isTrue();
                assertThat(props.resolveApp(CID_JP).getMockStableOpenid()).isEqualTo("jp-tester");
            });
    }

    @Test
    @DisplayName("fail fast: default-client-id 不在 apps 里 → 启动直接失败（不留到运行期）")
    void mapForm_defaultClientIdNotRegistered_failsStartup() {
        runner.withPropertyValues(
                "wx.miniapp.default-client-id=mp-applet-typo",
                "wx.miniapp.apps." + CID_GUZI + ".appid=" + REAL_APPID)
            .run(context -> assertThat(context).hasFailed());
    }

    /** 只装微信能力相关 Bean 的最小配置（不含 DB / Redis / web，起容器不依赖外部环境）。 */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(WxMiniappProperties.class)
    @Import({
        WxAppResolver.class,
        WxAdapterDispatcher.class,
        WxMockLoginAdapter.class,
        WxRealLoginAdapter.class,
        WxMockPhoneAdapter.class,
        WxRealPhoneAdapter.class,
        WxMockAccessTokenManager.class,
        WxRealAccessTokenManager.class,
        WxMockShippingClient.class,
        WxRealShippingClient.class
    })
    static class WxTestConfig {
    }
}
