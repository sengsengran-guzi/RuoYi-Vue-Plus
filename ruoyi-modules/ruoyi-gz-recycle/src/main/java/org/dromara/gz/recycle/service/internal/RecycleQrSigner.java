package org.dromara.gz.recycle.service.internal;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.gz.recycle.config.GzRecycleQrProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 回收到店核销码 HMAC-SHA256 签名工具（GZ-RECYCLE-004/T6，ADR-0012 §5 / 契约 15a §F）。
 *
 * <p>回收<b>自有 signer</b>（不依赖拼豆 {@code QrCodeSigner}——recycle 模块不依赖 gz-bean），仅借鉴同 HMAC 算法。
 * 签名因子 = {@code appointmentNo + appointmentId + expireEpochSec}（token 自带过期）；payload 前缀 {@code RC|}
 * 区别拼豆 {@code BK|}。用 JDK 内置 {@code Mac.getInstance("HmacSHA256")}，不引第三方依赖。</p>
 *
 * <pre>
 *   verifyCode = HMAC-SHA256(appointmentNo|appointmentId|expireEpochSec, secret) 截前 32 hex
 *   payload    = RC|{appointmentNo}|{appointmentId}|{expireEpochSec}|{verifyCode}
 * </pre>
 *
 * @author kevin-coder (sensenran-guzi · GZ-RECYCLE-004/T6)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RecycleQrSigner {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    /** 截取签名前 N 字符作 verify_code（与拼豆同口径） */
    private static final int VERIFY_CODE_LENGTH = 32;
    /** payload 前缀（区别拼豆 BK|，契约 §F.1） */
    public static final String PAYLOAD_PREFIX = "RC";
    /** payload 分隔符 */
    public static final String PAYLOAD_SEP = "|";
    /** payload 段数（RC | no | id | exp | code） */
    public static final int PAYLOAD_SEGMENTS = 5;

    private final GzRecycleQrProperties properties;

    /**
     * 生成核销码（HMAC-SHA256 截 32 hex）。
     *
     * @param appointmentNo  预约业务码
     * @param appointmentId  预约主键
     * @param expireEpochSec 过期时间戳（秒）
     * @return 32 位 hex verify_code
     */
    public String signRecycle(String appointmentNo, Long appointmentId, long expireEpochSec) {
        if (appointmentNo == null || appointmentId == null) {
            throw new ServiceException("RecycleQrSigner: appointmentNo/appointmentId 不能为空");
        }
        String payload = appointmentNo + PAYLOAD_SEP + appointmentId + PAYLOAD_SEP + expireEpochSec;
        return hmacSha256Hex(payload, properties.getSigningSecret()).substring(0, VERIFY_CODE_LENGTH);
    }

    /**
     * 校验核销码是否与 (appointmentNo + appointmentId + expireEpochSec) 匹配（店员扫码核对时调）。
     */
    public boolean verifyRecycle(String appointmentNo, Long appointmentId, long expireEpochSec, String verifyCode) {
        if (verifyCode == null) {
            return false;
        }
        return verifyCode.equals(signRecycle(appointmentNo, appointmentId, expireEpochSec));
    }

    /**
     * 构建 QR payload（mp 顾客端渲染二维码）。
     *
     * <p>格式：{@code RC|{appointmentNo}|{appointmentId}|{expireEpochSec}|{verifyCode}}。</p>
     */
    public String buildRecyclePayload(String appointmentNo, Long appointmentId, long expireEpochSec, String verifyCode) {
        return PAYLOAD_PREFIX + PAYLOAD_SEP + appointmentNo + PAYLOAD_SEP + appointmentId
            + PAYLOAD_SEP + expireEpochSec + PAYLOAD_SEP + verifyCode;
    }

    /** 核销码 TTL（秒），用于即时签发 expireEpochSec = now + ttl。 */
    public long getTtlSeconds() {
        return properties.getVerifyCodeTtlSeconds();
    }

    /** HMAC-SHA256 → hex 字符串。 */
    private String hmacSha256Hex(String data, String secret) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            SecretKeySpec keySpec = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM);
            mac.init(keySpec);
            byte[] hash = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException | InvalidKeyException ex) {
            log.error("[recycle-qr-signer] HMAC-SHA256 init failed", ex);
            throw new ServiceException("核销码生成失败：HMAC 初始化异常");
        }
    }
}
