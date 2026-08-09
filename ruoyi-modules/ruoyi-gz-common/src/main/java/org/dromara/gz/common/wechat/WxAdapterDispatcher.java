package org.dromara.gz.common.wechat;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.gz.common.pay.shipping.WxShippingClient;
import org.dromara.gz.common.pay.shipping.impl.WxMockShippingClient;
import org.dromara.gz.common.pay.shipping.impl.WxRealShippingClient;
import org.dromara.gz.common.wechat.WxMiniappProperties.MiniappApp;
import org.dromara.gz.common.wechat.impl.WxMockAccessTokenManager;
import org.dromara.gz.common.wechat.impl.WxMockLoginAdapter;
import org.dromara.gz.common.wechat.impl.WxMockPhoneAdapter;
import org.dromara.gz.common.wechat.impl.WxRealAccessTokenManager;
import org.dromara.gz.common.wechat.impl.WxRealLoginAdapter;
import org.dromara.gz.common.wechat.impl.WxRealPhoneAdapter;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * 微信能力 real / mock <b>运行时</b>分派器（ADR-0019 §1）。
 *
 * <p><b>取代了什么</b>：改造前 real / mock 由 8 处启动期条件 Bean 全局互斥
 * （real 侧 {@code @ConditionalOnExpression("'${wx.miniapp.appid:wxMOCK}' != 'wxMOCK'"}）、
 * mock 侧对称 {@code @ConditionalOnProperty(havingValue="wxMOCK", matchIfMissing=true)}），
 * JVM 启动那一刻整个进程只能整体 real 或整体 mock。但 mock/real 是<b>每个小程序各自的属性</b>：
 * 新小程序 dev 阶段还没拿到 secret 必须走 mock，而现小程序 prod 已经是 real —— 进程级粒度表达不了。</p>
 *
 * <p><b>现在的模型</b>：8 个实现<b>全部注册</b>为普通 Bean，本类作为 {@code @Primary} 门面同时实现
 * 四个能力接口（{@link WxLoginAdapter} / {@link WxPhoneAdapter} / {@link WxAccessTokenManager} /
 * {@link WxShippingClient}），业务侧注入接口拿到的就是本门面，按当前请求 clientid → app → mode
 * 逐次选实现。业务代码零改动。</p>
 *
 * <p><b>为什么 real 实现不注入本门面</b>：{@link WxRealPhoneAdapter} / {@link WxRealShippingClient}
 * 直接依赖具体的 {@link WxRealAccessTokenManager}（不是 {@code @Primary} 门面）——
 * 否则 dispatcher → realPhoneAdapter → dispatcher 构成构造器循环依赖，Spring Boot 3 默认直接启动失败。
 * 语义上也更准：real 通道只会在当前 app 是 real 时被选中，它要的就是 real token 管理器。</p>
 *
 * @author kevin-coder (sensenran-guzi)
 */
@Slf4j
@Primary
@Component
@RequiredArgsConstructor
public class WxAdapterDispatcher implements WxLoginAdapter, WxPhoneAdapter, WxAccessTokenManager, WxShippingClient {

    private final WxAppResolver appResolver;

    private final WxMockLoginAdapter mockLoginAdapter;
    private final WxRealLoginAdapter realLoginAdapter;
    private final WxMockPhoneAdapter mockPhoneAdapter;
    private final WxRealPhoneAdapter realPhoneAdapter;
    private final WxMockAccessTokenManager mockAccessTokenManager;
    private final WxRealAccessTokenManager realAccessTokenManager;
    private final WxMockShippingClient mockShippingClient;
    private final WxRealShippingClient realShippingClient;

    // ---------------------------------------------------------------- 选实现

    /**
     * 当前请求所属小程序（<b>严格</b>：未登记的 clientid 在此抛错，不静默落默认 app）。
     *
     * <p>只对「只可能被小程序调用」的能力用这个口径 —— 见 {@link WxAppResolver} 类注释里
     * 严格 / 宽松两种口径的分界。</p>
     */
    public MiniappApp currentApp() {
        return appResolver.currentApp();
    }

    /** 当前小程序的登录适配器（mp 专属能力 → 严格口径）。 */
    public WxLoginAdapter loginAdapter() {
        return pick(appResolver.currentApp(), mockLoginAdapter, realLoginAdapter);
    }

    /** 当前小程序的手机号适配器（mp 专属能力 → 严格口径）。 */
    public WxPhoneAdapter phoneAdapter() {
        return pick(appResolver.currentApp(), mockPhoneAdapter, realPhoneAdapter);
    }

    /**
     * 当前小程序的 access_token 管理器（<b>共享能力 → 宽松口径</b>）。
     *
     * <p>消费方含 admin 后台手动补报链路，调用线程带的是 PC clientid，认不出属正常。</p>
     */
    public WxAccessTokenManager accessTokenManager() {
        return pick(appResolver.currentAppOrDefault(), mockAccessTokenManager, realAccessTokenManager);
    }

    /**
     * 当前小程序的发货信息上报客户端（<b>共享能力 → 宽松口径</b>）。
     *
     * <p>四种调用来源：支付回调后的 {@code @Async} 线程、店员点发货后的 {@code @Async} 线程、cron、
     * 以及 admin 后台「发货信息手动补报」（{@code POST /system/gz/pay/shipping/{id}/retry}，带的是 PC clientid）。
     * 用严格口径会让后台补报按钮直接报「未配置的小程序客户端」—— 那是本能力唯一的人工兜底，不能挂。</p>
     *
     * @param clientId 发货任务自带的归属 clientid；blank → 回落当前请求 / 默认 app
     */
    public WxShippingClient shippingClient(String clientId) {
        return pick(appResolver.appOfOrDefault(clientId), mockShippingClient, realShippingClient);
    }

    /** 当前请求 / 默认小程序的发货信息上报客户端（无归属信息时用）。 */
    public WxShippingClient shippingClient() {
        return shippingClient(null);
    }

    private <T> T pick(MiniappApp app, T mock, T real) {
        if (log.isDebugEnabled()) {
            log.debug("[wx-dispatch] clientid={} appid={} → {}",
                app.getClientId(), app.getAppid(), app.isMock() ? "mock" : "real");
        }
        return app.isMock() ? mock : real;
    }

    // ---------------------------------------------------------------- 门面转发

    @Override
    public WxJscode2SessionResult code2Session(String code) {
        return loginAdapter().code2Session(code);
    }

    @Override
    public String code2Phone(String code) {
        return phoneAdapter().code2Phone(code);
    }

    @Override
    public String getToken(boolean forceRefresh) {
        return accessTokenManager().getToken(forceRefresh);
    }

    /**
     * 发货信息上报门面转发。
     *
     * <p><b>★ 按 {@link UploadCommand#clientId()} 选通道，不按当前请求</b>：上报几乎总在无请求上下文的
     * 线程里发生（{@code @Async} / cron / admin 补报），按当前请求选会让拼团（mock 或另一 appid）的订单
     * 走到现小程序的 real 通道上去。</p>
     */
    @Override
    public UploadResult uploadShippingInfo(UploadCommand cmd) {
        return shippingClient(cmd == null ? null : cmd.clientId()).uploadShippingInfo(cmd);
    }

    /**
     * 当前小程序的通道名（{@link WxLoginAdapter#channel()} 与 {@link WxPhoneAdapter#channel()} 同签名，
     * 且两者取值恒等于当前 app 的 mode，故一个实现即可）。
     *
     * @return "mock" / "real"
     */
    @Override
    public String channel() {
        return currentApp().isMock() ? "mock" : "real";
    }
}
