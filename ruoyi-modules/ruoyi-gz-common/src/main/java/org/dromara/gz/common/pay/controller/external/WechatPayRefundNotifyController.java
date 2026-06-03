package org.dromara.gz.common.pay.controller.external;

import cn.dev33.satoken.annotation.SaIgnore;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.tenant.helper.TenantHelper;
import org.dromara.gz.common.pay.domain.entity.GzPayCallbackLog;
import org.dromara.gz.common.pay.mapper.GzPayCallbackLogMapper;
import org.dromara.gz.common.pay.service.IPayRefundService;
import org.dromara.gz.common.pay.service.internal.IWechatPayClient.NotifyContext;
import org.dromara.gz.common.pay.service.internal.WechatPayVerifyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.stream.Collectors;

/**
 * GZ-PAY-103 微信退款回调入口（AC 3，doc/10 §6.N10）。
 *
 * <p><b>独立 endpoint {@code /api/pay/v3/refund-notify}，无 sa-token</b>（{@link SaIgnore}）—— 与 PAY-001
 * 支付回调 {@code /api/pay/v3/notify} 完全分离（强约束 #4 / 决策 D1）。复用同一套 V3 NotificationParser
 * AES-GCM 验签 + 解密机制（强约束 #2），但解析为退款回调模型推进退款单终态 + 触发退款 SPI。</p>
 *
 * <p><b>响应契约</b>（微信 V3）：处理成功 → 200 SUCCESS；验签失败 → 401（微信重试，写 callback_log failed
 * 告警）；业务异常 → 500（微信重试）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-PAY-103)
 */
@Slf4j
@SaIgnore
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/pay/v3")
public class WechatPayRefundNotifyController {

    private static final String RESP_SUCCESS = "{\"code\":\"SUCCESS\",\"message\":\"成功\"}";
    private static final String RESP_FAIL = "{\"code\":\"FAIL\",\"message\":\"验签或处理失败\"}";

    private final IPayRefundService refundService;
    private final GzPayCallbackLogMapper callbackLogMapper;

    /**
     * 退款结果回调（微信 POST AES-GCM 加密 body）。
     */
    @PostMapping("/refund-notify")
    public ResponseEntity<String> refundNotify(HttpServletRequest request) {
        String body = readBody(request);
        NotifyContext ctx = new NotifyContext(
            request.getHeader("Wechatpay-Timestamp"),
            request.getHeader("Wechatpay-Nonce"),
            request.getHeader("Wechatpay-Signature"),
            request.getHeader("Wechatpay-Serial"),
            body);

        return TenantHelper.ignore(() -> {
            try {
                boolean ok = refundService.handleRefundNotify(ctx);
                if (ok) {
                    return ResponseEntity.ok(RESP_SUCCESS);
                }
                log.error("[gz-pay] 退款回调业务处理失败 body={}", trunc(body));
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(RESP_FAIL);
            } catch (WechatPayVerifyException e) {
                writeFailedLog(body, ctx.signature(), e.getMessage());
                log.error("[gz-pay] 退款回调验签失败（疑似伪造请求）sig={} body={}", ctx.signature(), trunc(body), e);
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(RESP_FAIL);
            } catch (Exception e) {
                log.error("[gz-pay] 退款回调处理异常 body={}", trunc(body), e);
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(RESP_FAIL);
            }
        });
    }

    private void writeFailedLog(String rawBody, String signature, String error) {
        try {
            GzPayCallbackLog cbLog = GzPayCallbackLog.builder()
                .callbackType("refund")
                .rawBody(rawBody == null ? "" : rawBody)
                .signature(signature)
                .processStatus("failed")
                .processError(trunc(error))
                .build();
            callbackLogMapper.insert(cbLog);
        } catch (Exception ex) {
            log.error("[gz-pay] 写退款回调验签失败审计日志失败", ex);
        }
    }

    private String readBody(HttpServletRequest request) {
        try (BufferedReader reader = request.getReader()) {
            return reader.lines().collect(Collectors.joining(System.lineSeparator()));
        } catch (IOException e) {
            log.error("[gz-pay] 读取退款回调 body 失败", e);
            return "";
        }
    }

    private static String trunc(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 480 ? s.substring(0, 480) : s;
    }
}
