package org.dromara.gz.bean.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 拼豆核销码签名配置（GZ-BEAN-004）。
 *
 * <p>从 application-{env}.yml 注入 {@code gz.bean.qr.signing-secret}。</p>
 *
 * <p><b>密钥分级</b>（doc/10 §3 ticket R5）：</p>
 * <ul>
 *   <li>dev — 硬编码 fallback {@code dev-sensenran-guzi-qr-secret-2026}（YAML 默认值）</li>
 *   <li>staging / prod — env var {@code GZ_BEAN_QR_SECRET}，不入 git</li>
 * </ul>
 *
 * @author kevin-coder (sensenran-guzi · GZ-BEAN-004)
 */
@Data
@Component
@ConfigurationProperties(prefix = "gz.bean.qr")
public class GzBeanQrProperties {

    /**
     * 核销码 HMAC-SHA256 签名密钥。
     *
     * <p>生产环境从 env var 注入；dev 用 YAML 默认值。</p>
     */
    private String signingSecret = "dev-sensenran-guzi-qr-secret-2026";
}
