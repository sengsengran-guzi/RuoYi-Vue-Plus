package org.dromara.gz.common.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 生产环境敏感配置 fail-fast 守卫（D16 B3）。
 *
 * <p><b>背景</b>：{@code application-prod.yml} 把 {@code wx.miniapp.appid} 默认成 {@code wxMOCK}、
 * {@code gz.bean.qr.signing-secret} 默认成 {@code CHANGE_ME_BEFORE_PROD_DEPLOY}。若运维漏注入对应
 * env var（合同明确正式 AppID 待甲方 = 高风险窗口），后端会<b>静默</b>启用 mock 登录适配器
 * （{@code WxMockLoginAdapter} {@code matchIfMissing=true}）—— 任意 code 即拿稳定 mock openid + token，
 * 鉴权完全失效；核销码 HMAC 用占位密钥可被伪造。与「mock 支付是已知外部 gate」不同，这是<b>鉴权静默降级</b>，
 * 无显式提示。</p>
 *
 * <p><b>对策</b>：prod profile 启动时校验上述敏感项是否仍为占位值，是则<b>拒绝启动</b>
 * （照 {@code WechatPayV3ClientImpl} real 模式 fail-fast 范式）。dev / 测试 profile 不受影响
 * （仍可用 wxMOCK 做 mock 联调）。正式 AppID / QR 密钥由甲方提供后注入 env var 即正常启动。</p>
 *
 * @author kevin-coder (sensenran-guzi · D16 B3 prod 漏配守卫)
 */
@Slf4j
@Configuration
@Profile("prod")
@RequiredArgsConstructor
public class ProdSecretGuard {

    /** 不允许在 prod 出现的占位值（出现即视为漏配）。 */
    static final Set<String> APPID_PLACEHOLDERS = Set.of("wxMOCK");
    static final Set<String> QR_SECRET_PLACEHOLDERS = Set.of(
        "CHANGE_ME_BEFORE_PROD_DEPLOY", "dev-sensenran-guzi-qr-secret-2026");

    private final Environment environment;

    @PostConstruct
    public void check() {
        String appid = environment.getProperty("wx.miniapp.appid");
        String qrSecret = environment.getProperty("gz.bean.qr.signing-secret");
        String payClientMode = environment.getProperty("gz.pay.client-mode");
        validate(appid, qrSecret, payClientMode);
        log.info("[gz-prod-guard] 生产敏感配置校验通过（appid / qr-secret / pay client-mode 已注入正式值）");
    }

    /**
     * 旧签名保留（仅校验 appid / qr-secret）：既有单测沿用，clientMode 视为已注入正式值。
     */
    static void validate(String appid, String qrSecret) {
        validate(appid, qrSecret, "real");
    }

    /**
     * 校验 prod 敏感项；任一仍为占位/不安全值则抛 {@link IllegalStateException}（一次列全所有漏配项）。
     * 抽成静态方法便于单测（不启 Spring context）。
     */
    static void validate(String appid, String qrSecret, String payClientMode) {
        List<String> missing = new ArrayList<>();
        if (appid == null || appid.isBlank() || APPID_PLACEHOLDERS.contains(appid)) {
            missing.add("wx.miniapp.appid（env WX_MA_APPID）仍为占位/空 [" + appid + "] → mock 登录会静默生效，鉴权失效");
        }
        if (qrSecret == null || qrSecret.isBlank() || QR_SECRET_PLACEHOLDERS.contains(qrSecret)) {
            missing.add("gz.bean.qr.signing-secret（env GZ_BEAN_QR_SECRET）仍为占位/空 → 核销码 HMAC 可被伪造");
        }
        if (payClientMode == null || payClientMode.isBlank() || !"real".equalsIgnoreCase(payClientMode.trim())) {
            missing.add("gz.pay.client-mode（env WECHAT_PAY_CLIENT_MODE）非 real [" + payClientMode
                + "] → mock 支付客户端会在 prod 生效，/api/pay/v3/notify 回调端点零验签，任意人可伪造回调把订单刷成已支付");
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                "[prod 启动拒绝] 以下生产敏感配置未注入正式值，禁止上线运行：\n  - "
                    + String.join("\n  - ", missing)
                    + "\n请在生产环境注入对应 env var 后重启（dev/测试 profile 不受此约束）。");
        }
    }
}
