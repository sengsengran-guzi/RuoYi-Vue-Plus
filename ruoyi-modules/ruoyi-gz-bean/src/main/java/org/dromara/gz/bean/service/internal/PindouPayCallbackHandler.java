package org.dromara.gz.bean.service.internal;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.gz.bean.service.IGzBeanBookingService;
import org.dromara.gz.common.pay.domain.entity.GzPayTransaction;
import org.dromara.gz.common.pay.enums.PayBusinessType;
import org.dromara.gz.common.pay.service.spi.PayCallbackHandler;
import org.springframework.stereotype.Component;

/**
 * 拼豆付费支付成功回调 handler（GZ-BEAN-014 AC 5，激活 {@code PayBusinessType.PINDOU}）。
 *
 * <p>实现 gz-common 的 {@link PayCallbackHandler} SPI，{@link org.dromara.gz.common.pay.service.spi.PayCallbackDispatcher}
 * 按 {@link #supportedBusinessType()} = {@code pindou} 收集为路由表。微信收款回调成功后，PAY-101
 * 在<b>同一回调事务</b>内调 {@link #onPaid}（doc/10 §11.N9）。</p>
 *
 * <p><b>事务边界</b>（同 SPI 约定）：本 handler 在回调事务内只更新拼豆 booking 业务状态
 * （pay_status paying → paid + 生成核销码），不触发新支付 / 不调外部不可回滚操作；抛异常 →
 * 整笔回调事务回滚（交易行保 pending）→ 微信重试 + PAY-102 主动查单兜底。</p>
 *
 * <p><b>幂等</b>：PAY-101 保证同一交易 onPaid 至多调一次；{@link IGzBeanBookingService#onPindouPaid}
 * 内再加 {@code pay_status='paying'} 条件 UPDATE 守卫双保险。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-BEAN-014)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PindouPayCallbackHandler implements PayCallbackHandler {

    private final IGzBeanBookingService bookingService;

    @Override
    public String supportedBusinessType() {
        return PayBusinessType.PINDOU;
    }

    @Override
    public void onPaid(GzPayTransaction txn) {
        // business_order_no = gz_bean_booking.booking_no（submitPaid 建支付单时传入）
        String bookingNo = txn.getBusinessOrderNo();
        log.info("[pindou-onpaid] 收到拼豆支付回调 bookingNo={} outTradeNo={} amount={}",
            bookingNo, txn.getOutTradeNo(), txn.getAmountCent());
        bookingService.onPindouPaid(bookingNo, txn.getOutTradeNo());
    }
}
