package org.dromara.gz.bean.service.internal;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.gz.bean.service.IGzBeanBookingService;
import org.dromara.gz.common.pay.domain.entity.GzPayRefund;
import org.dromara.gz.common.pay.domain.entity.GzPayTransaction;
import org.dromara.gz.common.pay.enums.PayBusinessType;
import org.dromara.gz.common.pay.service.spi.IRefundCallbackHandler;
import org.springframework.stereotype.Component;

/**
 * 拼豆付费退款成功回调 handler（D16 P2，实现 PAY-103 退款 SPI）。
 *
 * <p><b>背景</b>：拼豆 V1.2 已激活付费 + 配额；admin 对已支付拼豆单全额退款时，
 * {@code RefundCallbackDispatcher} 按 {@code business_type='pindou'} 分发，但此前<b>无 pindou 退款 handler</b>
 * → dispatcher 静默跳过 → {@code gz_bean_booking} 仍 paid/pending → 配额被已退款单永久占用（防超卖反噬）+
 * 用户「我的预约」仍显示已支付/可核销（业务与资金账不一致）。本 handler 补齐该链路。</p>
 *
 * <p><b>事务边界</b>（PAY-103 SPI 契约）：{@link #onRefunded} 在退款回调事务内（REQUIRED）被调用，
 * 退款单 + 交易行已置 refunded。本 handler 委托 {@link IGzBeanBookingService#onPindouRefunded}：
 * {@code pay_status paid → refunded}，未核销单 {@code status → cancelled} 释放配额；同事务，抛异常整笔回滚。</p>
 *
 * <p><b>券口径（保守默认）</b>：退款只退实付（= 单笔金额 − 券面额），已 used 的券<b>不退还</b>
 * （券让利已消费，退钱又退券 = 双重让利）。如甲方要退券，在 {@code onPindouRefunded} 内加券回退一行。</p>
 *
 * @author kevin-coder (sensenran-guzi · D16 P2)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PindouRefundCallbackHandler implements IRefundCallbackHandler {

    private final IGzBeanBookingService bookingService;

    @Override
    public String supportedBusinessType() {
        return PayBusinessType.PINDOU;
    }

    @Override
    public void onRefunded(GzPayRefund refund, GzPayTransaction txn) {
        // business_order_no = gz_bean_booking.booking_no（submitPaid 建支付单时传入）
        String bookingNo = txn.getBusinessOrderNo();
        log.info("[pindou-refund] 收到拼豆退款回调 refund_no={} bookingNo={} amount={}（释放配额，已用券不退还）",
            refund.getRefundNo(), bookingNo, refund.getRefundAmountCent());
        bookingService.onPindouRefunded(bookingNo);
    }
}
