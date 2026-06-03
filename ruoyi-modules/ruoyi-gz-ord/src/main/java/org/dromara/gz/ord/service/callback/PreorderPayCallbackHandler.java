package org.dromara.gz.ord.service.callback;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.gz.common.pay.domain.entity.GzPayTransaction;
import org.dromara.gz.common.pay.enums.PayBusinessType;
import org.dromara.gz.common.pay.service.spi.PayCallbackHandler;
import org.dromara.gz.ord.service.IGzOrdOrderService;
import org.springframework.stereotype.Component;

/**
 * 预购（业务线 A）支付成功回调 handler（GZ-ORD-104 AC3，实现 PAY-101 支付 SPI）。
 *
 * <p><b>架构：处理器下沉 gz-ord</b>（ticket ⭐⭐）：gz-common <b>不能依赖</b> gz-ord（gz-ord→gz-common
 * 单向，反向 = 循环依赖），故真实出单逻辑必须在 gz-ord 实现本 handler。PAY-101 时期 gz-common 的
 * {@code spi.handler.PreorderPayCallbackHandler} 占位已删除（否则两个 preorder 支付 handler →
 * {@code PayCallbackDispatcher} 启动 fail-fast 抛 IllegalStateException）。</p>
 *
 * <p><b>事务边界</b>（PAY-101 SPI 契约）：{@link #onPaid} 在 PAY-101 {@code handlePaymentNotify}
 * 回调事务内（REQUIRED 传播）被调用，交易行已置 paid。本 handler 委托
 * {@link IGzOrdOrderService#onPaid} 把 {@code gz_ord_order}（business_order_no = order_no）
 * created → paid（同事务）。抛异常 → 整笔回调事务回滚（交易行回 pending）→ 微信重试 + PAY-102 兜底。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-ORD-104)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PreorderPayCallbackHandler implements PayCallbackHandler {

    private final IGzOrdOrderService ordOrderService;

    @Override
    public String supportedBusinessType() {
        return PayBusinessType.PREORDER;
    }

    @Override
    public void onPaid(GzPayTransaction txn) {
        log.info("[gz-ord] 预购支付回调命中 out_trade_no={} business_order_no={} transaction_id={}",
            txn.getOutTradeNo(), txn.getBusinessOrderNo(), txn.getTransactionId());
        ordOrderService.onPaid(txn);
    }
}
