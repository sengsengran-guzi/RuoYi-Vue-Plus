package org.dromara.gz.common.pay.controller.external;

import cn.dev33.satoken.annotation.SaIgnore;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.tenant.helper.TenantHelper;
import org.dromara.gz.common.pay.domain.entity.GzPayCallbackLog;
import org.dromara.gz.common.pay.mapper.GzPayCallbackLogMapper;
import org.dromara.gz.common.pay.service.IGzPayTransactionService;
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
 * GZ-PAY-001 微信支付回调入口（AC 6，doc/10 §2.N6 → N8 / E2）。
 *
 * <p><b>对外路径 {@code /api/pay/v3/notify}，无 sa-token</b>（{@link SaIgnore}，对微信开放，强约束 #5）。
 * 安全靠验签（SDK NotificationParser）+ 微信 IP 白名单（prod 由运维加 nginx 层）。</p>
 *
 * <p><b>响应契约</b>（微信 V3 文档）：</p>
 * <ul>
 *   <li>处理成功 → HTTP 200 + {@code {"code":"SUCCESS","message":"成功"}}</li>
 *   <li>验签失败 → HTTP 401 + {@code {"code":"FAIL"...}}（微信会重试；失败不静默 — 写 callback_log failed + log.error 告警）</li>
 *   <li>业务异常 → HTTP 500（微信重试）</li>
 * </ul>
 *
 * <p>多租户：回调无登录态 → {@code TenantHelper.ignore} 包裹（callback_log / transaction 落 tenant '1001'
 * 由拦截器在 ignore 上下文按默认租户处理）。V1.0 单租户场景安全。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-PAY-001)
 */
@Slf4j
@SaIgnore
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/pay/v3")
public class WechatPayNotifyController {

    private static final String RESP_SUCCESS = "{\"code\":\"SUCCESS\",\"message\":\"成功\"}";
    private static final String RESP_FAIL = "{\"code\":\"FAIL\",\"message\":\"验签或处理失败\"}";

    private final IGzPayTransactionService transactionService;
    private final GzPayCallbackLogMapper callbackLogMapper;

    /**
     * 支付结果回调（微信 POST AES-GCM 加密 body）。
     */
    @PostMapping("/notify")
    public ResponseEntity<String> notify(HttpServletRequest request) {
        String body = readBody(request);
        NotifyContext ctx = new NotifyContext(
            request.getHeader("Wechatpay-Timestamp"),
            request.getHeader("Wechatpay-Nonce"),
            request.getHeader("Wechatpay-Signature"),
            request.getHeader("Wechatpay-Serial"),
            body);

        return TenantHelper.ignore(() -> {
            try {
                boolean ok = transactionService.handlePaymentNotify(ctx);
                if (ok) {
                    return ResponseEntity.ok(RESP_SUCCESS);
                }
                // 业务处理失败（订单不存在等）→ 500 让微信重试
                log.error("[gz-pay] 回调业务处理失败 body={}", trunc(body));
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(RESP_FAIL);
            } catch (WechatPayVerifyException e) {
                // 验签失败 → 写 callback_log failed + log.error 告警（不静默，AC 6/8）+ 401
                writeFailedLog(body, ctx.signature(), e.getMessage());
                log.error("[gz-pay] 回调验签失败（疑似伪造请求）sig={} body={}", ctx.signature(), trunc(body), e);
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(RESP_FAIL);
            } catch (Exception e) {
                log.error("[gz-pay] 回调处理异常 body={}", trunc(body), e);
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(RESP_FAIL);
            }
        });
    }

    private void writeFailedLog(String rawBody, String signature, String error) {
        try {
            GzPayCallbackLog log = GzPayCallbackLog.builder()
                .callbackType("payment")
                .rawBody(rawBody == null ? "" : rawBody)
                .signature(signature)
                .processStatus("failed")
                .processError(trunc(error))
                .build();
            callbackLogMapper.insert(log);
        } catch (Exception ex) {
            log.error("[gz-pay] 写验签失败审计日志失败", ex);
        }
    }

    private String readBody(HttpServletRequest request) {
        try (BufferedReader reader = request.getReader()) {
            return reader.lines().collect(Collectors.joining(System.lineSeparator()));
        } catch (IOException e) {
            log.error("[gz-pay] 读取回调 body 失败", e);
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
