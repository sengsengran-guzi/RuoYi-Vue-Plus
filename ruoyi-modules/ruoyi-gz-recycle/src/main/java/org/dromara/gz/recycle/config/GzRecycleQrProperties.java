package org.dromara.gz.recycle.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 回收到店核销码签名配置（GZ-RECYCLE-004/T6，ADR-0012 §5 / 契约 15a §F）。
 *
 * <p>回收核销码自有 signer（{@link org.dromara.gz.recycle.service.internal.RecycleQrSigner}），HMAC-SHA256
 * 同算法、payload 前缀 {@code RC|} 区别拼豆 {@code BK|}。配置前缀 {@code gz.recycle.qr}：</p>
 * <ul>
 *   <li>dev — 硬编码 fallback（YAML 默认值，与拼豆 dev secret 同值即可，token 自带 RC 前缀 + 过期已区分）</li>
 *   <li>staging / prod — env var {@code GZ_RECYCLE_QR_SECRET}（或共用拼豆 secret，按部署注入），不入 git</li>
 * </ul>
 *
 * <p>核销码 token 自带过期（payload 含 expireEpochSec），TTL 由 {@link #verifyCodeTtlSeconds} 控制。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-RECYCLE-004/T6)
 */
@Data
@Component
@ConfigurationProperties(prefix = "gz.recycle.qr")
public class GzRecycleQrProperties {

    /**
     * 核销码 HMAC-SHA256 签名密钥（生产从 env 注入；dev 用 YAML 默认值）。
     */
    private String signingSecret = "dev-sensenran-guzi-qr-secret-2026";

    /**
     * 核销码有效期（秒），默认 24h。签发时 expireEpochSec = now + 此值；校验时比 now。
     */
    private long verifyCodeTtlSeconds = 24 * 60 * 60L;
}
