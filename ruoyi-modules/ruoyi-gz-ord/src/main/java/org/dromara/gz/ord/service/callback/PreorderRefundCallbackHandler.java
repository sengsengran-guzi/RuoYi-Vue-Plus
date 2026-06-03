package org.dromara.gz.ord.service.callback;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.gz.common.pay.domain.entity.GzPayRefund;
import org.dromara.gz.common.pay.domain.entity.GzPayTransaction;
import org.dromara.gz.common.pay.enums.PayBusinessType;
import org.dromara.gz.common.pay.service.spi.IRefundCallbackHandler;
import org.dromara.gz.ord.service.IGzOrdOrderService;
import org.springframework.stereotype.Component;

/**
 * 预购（业务线 A）退款成功回调 handler（GZ-ORD-104，实现 PAY-103 退款 SPI）。
 *
 * <p><b>架构：处理器下沉 gz-ord</b>（ticket ⭐⭐）：PAY-103 时期 gz-common 的
 * {@code spi.handler.PreorderRefundCallbackHandler} 占位已删除（否则两个 preorder 退款 handler →
 * {@code RefundCallbackDispatcher} 启动 fail-fast）。</p>
 *
 * <p><b>事务边界</b>（PAY-103 SPI 契约）：{@link #onRefunded} 在 PAY-103 退款回调事务内（REQUIRED）
 * 被调用，退款单 + 交易行已置 refunded。本 handler 委托 {@link IGzOrdOrderService#onRefunded} 把
 * {@code gz_ord_order} paid → refunded（同事务）。<b>SKU 库存不归还</b>（货已采购，doc/10 §6.N10）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-ORD-104)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PreorderRefundCallbackHandler implements IRefundCallbackHandler {

    private final IGzOrdOrderService ordOrderService;

    @Override
    public String supportedBusinessType() {
        return PayBusinessType.PREORDER;
    }

    @Override
    public void onRefunded(GzPayRefund refund, GzPayTransaction txn) {
        log.info("[gz-ord] 预购退款回调命中 refund_no={} out_trade_no={} business_order_no={}（SKU 库存不归还）",
            refund.getRefundNo(), refund.getOutTradeNo(), txn.getBusinessOrderNo());
        ordOrderService.onRefunded(txn);
    }
}
