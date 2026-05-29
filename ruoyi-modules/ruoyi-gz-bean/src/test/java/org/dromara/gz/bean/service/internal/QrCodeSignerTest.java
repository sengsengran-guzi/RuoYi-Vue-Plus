package org.dromara.gz.bean.service.internal;

import org.dromara.gz.bean.config.GzBeanQrProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * QrCodeSigner 单元测试（GZ-BEAN-004）。
 *
 * <p>覆盖：</p>
 * <ul>
 *   <li>sign 输出长度固定 32 位（hex）</li>
 *   <li>verify 同输入匹配</li>
 *   <li>verify 改任一参数不匹配</li>
 *   <li>不同 bookingNo 产出不同 verifyCode</li>
 *   <li>不同 secret 产出不同 verifyCode</li>
 *   <li>buildQrPayload 格式 BK|{no}|{code}</li>
 * </ul>
 */
@Tag("dev")
class QrCodeSignerTest {

    private QrCodeSigner signer;
    private GzBeanQrProperties props;

    @BeforeEach
    void setUp() {
        props = new GzBeanQrProperties();
        props.setSigningSecret("test-secret-2026");
        signer = new QrCodeSigner(props);
    }

    @Test
    void sign_outputLengthIs32() {
        String code = signer.sign("BK20260601000001", LocalDate.of(2026, 6, 1), 100L);
        assertNotNull(code);
        assertEquals(32, code.length(), "verifyCode 必须 32 位 hex");
    }

    @Test
    void verify_sameInputMatches() {
        String code = signer.sign("BK20260601000001", LocalDate.of(2026, 6, 1), 100L);
        assertTrue(signer.verify("BK20260601000001", LocalDate.of(2026, 6, 1), 100L, code));
    }

    @Test
    void verify_differentBookingNoFails() {
        String code = signer.sign("BK20260601000001", LocalDate.of(2026, 6, 1), 100L);
        assertFalse(signer.verify("BK20260601000002", LocalDate.of(2026, 6, 1), 100L, code));
    }

    @Test
    void verify_differentDateFails() {
        String code = signer.sign("BK20260601000001", LocalDate.of(2026, 6, 1), 100L);
        assertFalse(signer.verify("BK20260601000001", LocalDate.of(2026, 6, 2), 100L, code));
    }

    @Test
    void verify_differentSeatIdFails() {
        String code = signer.sign("BK20260601000001", LocalDate.of(2026, 6, 1), 100L);
        assertFalse(signer.verify("BK20260601000001", LocalDate.of(2026, 6, 1), 101L, code));
    }

    @Test
    void sign_differentSecretsProduceDifferentCodes() {
        String code1 = signer.sign("BK20260601000001", LocalDate.of(2026, 6, 1), 100L);
        props.setSigningSecret("another-secret");
        String code2 = signer.sign("BK20260601000001", LocalDate.of(2026, 6, 1), 100L);
        assertNotEquals(code1, code2);
    }

    @Test
    void buildQrPayload_format() {
        String payload = signer.buildQrPayload("BK20260601000001", "abc123");
        assertEquals("BK|BK20260601000001|abc123", payload);
    }
}
