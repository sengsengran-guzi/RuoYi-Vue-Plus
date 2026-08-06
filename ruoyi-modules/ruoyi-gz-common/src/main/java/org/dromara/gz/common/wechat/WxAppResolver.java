package org.dromara.gz.common.wechat;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.dromara.common.core.utils.ServletUtils;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.gz.common.wechat.WxMiniappProperties.MiniappApp;
import org.springframework.stereotype.Component;

/**
 * 「当前请求属于哪个小程序」解析器（ADR-0019 §1）。
 *
 * <p>路由键 = 请求 header {@code clientid}（mp 端 http 拦截器统一注入：现小程序
 * {@code mp-applet-sensenran-guzi}、日本拼团 {@code mp-applet-gz-jp}）。选它做路由键是因为
 * {@code SecurityConfig} 的校验本就是 token extra 与 header 的纯字符串比对、不查 {@code sys_client} 表，
 * 新增客户端零成本，无需再引入一个新维度。</p>
 *
 * <p><b>⚠️ clientid 是 ruoyi 的「认证客户端」id，不等于「小程序」id</b> —— 小程序的 clientid 只是其中一部分。
 * plus-ui 后台给<b>每个</b>请求都注入 {@code clientid: e5cd7e4891bf95d1d19206ce24a7b32e}（PC 端客户端，
 * 见 {@code plus-ui/src/utils/request.ts}），它根本不是小程序。因此本类提供<b>两种</b>解析口径，
 * 调用方必须按自己的调用来源选对：</p>
 * <ul>
 *   <li>{@link #currentApp()} <b>严格</b> —— 用于<b>只可能被小程序调用</b>的能力（登录 / 手机号，
 *       控制器挂在 {@code /app/**}）。这种场景下 clientid 没登记 = 真配置错误（例如新小程序
 *       上线忘了在 {@code wx.miniapp.apps} 里登记），必须<b>立刻抛错</b>，绝不能静默拿另一个
 *       小程序的 appid/secret 去调微信（那会以「invalid code」的形态出现在运行期，极难定位）。</li>
 *   <li>{@link #currentAppOrDefault()} <b>宽松</b> —— 用于<b>共享能力</b>（发货信息上报 / access_token）：
 *       这些既会被小程序侧的支付回调触发，也会被 {@code @Async} 线程、cron、以及
 *       <b>后台 admin 手动补报</b>（{@code POST /system/gz/pay/shipping/{id}/retry}）触发。
 *       认不出的 clientid 只说明「调用方不是小程序」，落 {@code default-client-id} 即可 ——
 *       这正是多 appid 改造前的行为（当时整个进程只有一个 appid）。</li>
 * </ul>
 *
 * <p><b>无请求上下文时</b>（{@code @Async} 发货上报 / cron / 单测 / 启动期）两种口径都回落
 * {@code wx.miniapp.default-client-id}。</p>
 *
 * <p>本类刻意与 {@link WxAdapterDispatcher} 分开：real 侧实现（如 {@link
 * org.dromara.gz.common.wechat.impl.WxRealLoginAdapter}）也要按 app 取自己的 appid/secret，
 * 若直接依赖 dispatcher 会形成 dispatcher ↔ 实现 的构造器循环依赖。</p>
 *
 * @author kevin-coder (sensenran-guzi)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WxAppResolver {

    private final WxMiniappProperties properties;

    /**
     * 当前请求的 clientid；无请求上下文 / header 缺失 → {@code wx.miniapp.default-client-id}。
     *
     * <p>与 {@code SecurityConfig.check} 的契约保持一致：登录时写进 token extra 的 clientId 必须等于
     * mp 后续请求 header 里的 clientid，此处 header 优先即天然相等。</p>
     */
    public String currentClientId() {
        try {
            HttpServletRequest request = ServletUtils.getRequest();
            if (request != null) {
                String headerCid = request.getHeader(LoginHelper.CLIENT_KEY);
                if (StringUtils.isNotBlank(headerCid)) {
                    return headerCid;
                }
            }
        } catch (Exception e) {
            // 非 web 上下文或 ServletUtils 异常 — 静默走 fallback
            log.debug("[wx-app] 读取 clientid header 失败，回落默认: {}", e.getMessage());
        }
        return properties.getDefaultClientId();
    }

    /**
     * 【严格】当前请求对应的小程序配置 —— 仅用于<b>只可能被小程序调用</b>的能力（登录 / 手机号）。
     *
     * @throws org.dromara.common.core.exception.ServiceException clientid 未在 {@code wx.miniapp.apps} 登记
     */
    public MiniappApp currentApp() {
        return properties.resolveApp(currentClientId());
    }

    /**
     * 【宽松】当前请求对应的小程序配置，认不出就落默认 app —— 用于<b>共享能力</b>
     * （发货上报 / access_token：admin 后台手动补报、{@code @Async}、cron 都会走到）。
     *
     * <p>认不出 = 调用方不是小程序（如 plus-ui 后台的 PC clientid），不是配置错误，
     * 所以这里<b>不抛错</b>；落默认 app 即多 appid 改造前的行为。</p>
     */
    public MiniappApp currentAppOrDefault() {
        MiniappApp app = properties.resolveApps().get(currentClientId());
        if (app != null) {
            return app;
        }
        MiniappApp fallback = properties.resolveDefaultApp();
        log.debug("[wx-app] clientid={} 不是已登记的小程序（多半是后台/非 mp 调用方）→ 落默认 app {}",
            currentClientId(), fallback.getClientId());
        return fallback;
    }

    /** 指定 clientid 的小程序配置（异步任务已知归属时用，不依赖当前请求）。 */
    public MiniappApp appOf(String clientId) {
        return properties.resolveApp(clientId);
    }

    /** 当前请求对应的小程序 appid（微信各接口的凭证维度）；严格口径，仅 mp 侧调用点可用。 */
    public String currentAppid() {
        return currentApp().getAppid();
    }
}
