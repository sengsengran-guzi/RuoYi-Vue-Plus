package org.dromara.gz.jp.controller.external;

import cn.dev33.satoken.annotation.SaIgnore;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.tenant.helper.TenantHelper;
import org.dromara.gz.common.pay.domain.entity.GzPayCallbackLog;
import org.dromara.gz.common.pay.mapper.GzPayCallbackLogMapper;
import org.dromara.gz.common.pay.service.internal.IWechatPayClient.NotifyContext;
import org.dromara.gz.common.pay.service.internal.WechatPayVerifyException;
import org.dromara.gz.jp.service.IGzJpRefundService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.stream.Collectors;

/**
 * 拼团<b>行级退款</b>回调入口（GZ-JP-107，FLOW:F-JP-04.step3）。
 *
 * <p><b>★ 为什么不复用 {@code /api/pay/v3/refund-notify}</b>：那个入口按
 * {@code out_refund_no} 去 {@code gz_pay_refund} 找退款单，而拼团的退款单在 {@code gz_jp_refund}
 * —— 打过去必然「退款单不存在」返 500，微信按退避策略重试到放弃，
 * 结果是<b>钱已经退给客人了，系统里那笔单永远停在「退款中」</b>。
 * 退款请求的 {@code notify_url} 是<b>逐笔可指定</b>的，所以拼团用自己的地址，两条链路物理隔离。</p>
 *
 * <p><b>响应契约</b>（微信 V3，与 GZ-PAY 同款）：处理成功 → 200 SUCCESS；
 * 验签失败 → 401（写 callback_log failed 告警，微信重试）；业务异常 → 500（微信重试）。</p>
 *
 * <p><b>{@code @SaIgnore}</b>：微信的请求不带 token。<b>安全靠验签</b>（real 模式下
 * {@code parseAndVerifyRefundNotify} 用微信支付公钥验 {@code Wechatpay-Signature}），
 * 不靠地址保密。</p>
 *
 * <p><b>{@code TenantHelper.ignore}</b>：回调没有登录上下文，不忽略租户会让所有查询被
 * {@code AND tenant_id = null} 过滤成空 —— 表现为「退款单不存在」，极难查。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-JP-107)
 */
@Slf4j
@SaIgnore
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/gz/jp/pay")
public class GzJpRefundNotifyController {

    private static final String RESP_SUCCESS = "{\"code\":\"SUCCESS\",\"message\":\"成功\"}";
    private static final String RESP_FAIL = "{\"code\":\"FAIL\",\"message\":\"验签或处理失败\"}";

    private final IGzJpRefundService refundService;
    private final GzPayCallbackLogMapper callbackLogMapper;

    /**
     * 拼团行级退款结果回调（微信 POST AES-GCM 加密 body）。
     *
     * <pre>POST /api/gz/jp/pay/refund-notify</pre>
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
                if (refundService.handleRefundNotify(ctx)) {
                    return ResponseEntity.ok(RESP_SUCCESS);
                }
                log.error("[gz-jp-refund] 退款回调业务处理失败 body={}", trunc(body));
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(RESP_FAIL);
            } catch (WechatPayVerifyException e) {
                writeFailedLog(body, ctx.signature(), e.getMessage());
                log.error("[gz-jp-refund] 退款回调验签失败（疑似伪造请求）sig={} body={}",
                    ctx.signature(), trunc(body), e);
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(RESP_FAIL);
            } catch (Exception e) {
                log.error("[gz-jp-refund] 退款回调处理异常 body={}", trunc(body), e);
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(RESP_FAIL);
            }
        });
    }

    private void writeFailedLog(String rawBody, String signature, String error) {
        try {
            callbackLogMapper.insert(GzPayCallbackLog.builder()
                .callbackType("jp_refund")
                .rawBody(rawBody == null ? "" : rawBody)
                .signature(signature)
                .processStatus("failed")
                .processError(trunc(error))
                .build());
        } catch (Exception ex) {
            log.error("[gz-jp-refund] 写退款回调验签失败审计日志失败", ex);
        }
    }

    private String readBody(HttpServletRequest request) {
        try (BufferedReader reader = request.getReader()) {
            return reader.lines().collect(Collectors.joining(System.lineSeparator()));
        } catch (IOException e) {
            log.error("[gz-jp-refund] 读取退款回调 body 失败", e);
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
