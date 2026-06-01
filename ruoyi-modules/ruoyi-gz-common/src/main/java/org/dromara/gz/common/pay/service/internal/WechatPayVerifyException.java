package org.dromara.gz.common.pay.service.internal;

/**
 * 微信回调验签失败异常（GZ-PAY-001 AC 6）。
 *
 * <p>不吞异常（CLAUDE.md §6 #7）：回调验签失败时 throw 本异常 → controller catch →
 * 写 callback_log process_status='failed' + log.error 告警 + 返回 HTTP 401（微信会重试）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-PAY-001)
 */
public class WechatPayVerifyException extends RuntimeException {

    public WechatPayVerifyException(String message) {
        super(message);
    }

    public WechatPayVerifyException(String message, Throwable cause) {
        super(message, cause);
    }
}
