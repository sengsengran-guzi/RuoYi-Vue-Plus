package org.dromara.gz.bean.service.internal;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.gz.bean.config.GzBeanQrProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.security.InvalidKeyException;
import java.time.LocalDate;
import java.util.HexFormat;

/**
 * 核销码 HMAC-SHA256 签名工具（GZ-BEAN-004）。
 *
 * <p>doc/11 §3.6 verify_code 生成口径：</p>
 * <pre>
 *   verify_code = HMAC-SHA256(booking_no + sess_date + seat_id, signing-secret) 截前 32 字符
 * </pre>
 *
 * <p>用 JDK 内置 {@code Mac.getInstance("HmacSHA256")}，不引第三方依赖。</p>
 *
 * <p><b>独立 Bean</b>（便于单测 mock）：service 层依赖此接口而非直接调静态方法。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-BEAN-004)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QrCodeSigner {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    /** 截取签名前 N 字符作 verify_code（doc/11 §3.6） */
    private static final int VERIFY_CODE_LENGTH = 32;

    private final GzBeanQrProperties properties;

    /**
     * 生成 verify_code（核销码签名）。
     *
     * @param bookingNo  业务码
     * @param sessDate   预约日期
     * @param seatId     座位 ID
     * @return HMAC-SHA256 截 32 位 hex 字符串
     */
    public String sign(String bookingNo, LocalDate sessDate, Long seatId) {
        if (bookingNo == null || sessDate == null || seatId == null) {
            throw new ServiceException("QrCodeSigner: bookingNo/sessDate/seatId 不能为空");
        }
        String payload = bookingNo + "|" + sessDate + "|" + seatId;
        return hmacSha256Hex(payload, properties.getSigningSecret()).substring(0, VERIFY_CODE_LENGTH);
    }

    /**
     * 校验 verify_code 是否与 (bookingNo + sessDate + seatId) 匹配（admin 端核销时调）。
     */
    public boolean verify(String bookingNo, LocalDate sessDate, Long seatId, String verifyCode) {
        if (verifyCode == null) {
            return false;
        }
        return verifyCode.equals(sign(bookingNo, sessDate, seatId));
    }

    /**
     * 生成 verify_code（V1.2 付费模型 — 签名因子改 booking_no + sess_date + seat_type，doc/11 §3.8）。
     *
     * <p>V1.2 单笔单时段不再用废弃的 seat_id，改用本笔预约的座位类型作签名因子。</p>
     *
     * @param bookingNo 业务码
     * @param sessDate  预约日期
     * @param seatType  座位类型（single/double/quad）
     * @return HMAC-SHA256 截 32 位 hex 字符串
     */
    public String signByType(String bookingNo, LocalDate sessDate, String seatType) {
        if (bookingNo == null || sessDate == null || seatType == null) {
            throw new ServiceException("QrCodeSigner: bookingNo/sessDate/seatType 不能为空");
        }
        String payload = bookingNo + "|" + sessDate + "|" + seatType;
        return hmacSha256Hex(payload, properties.getSigningSecret()).substring(0, VERIFY_CODE_LENGTH);
    }

    /**
     * 校验 verify_code 是否与 (bookingNo + sessDate + seatType) 匹配（V1.2 付费单核销时调）。
     */
    public boolean verifyByType(String bookingNo, LocalDate sessDate, String seatType, String verifyCode) {
        if (verifyCode == null) {
            return false;
        }
        return verifyCode.equals(signByType(bookingNo, sessDate, seatType));
    }

    /**
     * 生成 QR payload 字符串（mp 端用此渲染二维码）。
     *
     * <p>格式：{@code "BK|{bookingNo}|{verifyCode}"} — 短码长度 ~50 字符。</p>
     */
    public String buildQrPayload(String bookingNo, String verifyCode) {
        return "BK|" + bookingNo + "|" + verifyCode;
    }

    /**
     * HMAC-SHA256 → hex 字符串。
     */
    private String hmacSha256Hex(String data, String secret) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            SecretKeySpec keySpec = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM);
            mac.init(keySpec);
            byte[] hash = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException | InvalidKeyException ex) {
            log.error("[qr-signer] HMAC-SHA256 init failed", ex);
            throw new ServiceException("核销码生成失败：HMAC 初始化异常");
        }
    }
}
