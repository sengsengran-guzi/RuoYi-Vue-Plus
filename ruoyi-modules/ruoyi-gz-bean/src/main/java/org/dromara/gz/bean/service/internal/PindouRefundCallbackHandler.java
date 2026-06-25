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
 * <p><b>券口径（甲方拍板：退款 = 退实付 + 退券恢复可用）</b>：退实付（= 单笔金额 − 券面额）后，
 * 未核销消费单的已 used 券回退为 unused（可再用）；已核销消费单（status=used）不退券（服务已享用）。
 * 券回退在 {@link IGzBeanBookingService#onPindouRefunded} 内随退款确认一并完成。</p>
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
        log.info("[pindou-refund] 收到拼豆退款回调 refund_no={} bookingNo={} amount={}（释放配额，未核销单退券恢复可用）",
            refund.getRefundNo(), bookingNo, refund.getRefundAmountCent());
        bookingService.onPindouRefunded(bookingNo);
    }
}
