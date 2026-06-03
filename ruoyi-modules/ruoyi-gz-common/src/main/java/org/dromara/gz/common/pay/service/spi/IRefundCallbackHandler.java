package org.dromara.gz.common.pay.service.spi;

import org.dromara.gz.common.pay.domain.entity.GzPayRefund;
import org.dromara.gz.common.pay.domain.entity.GzPayTransaction;

/**
 * 退款成功回调业务分发 SPI（GZ-PAY-103 AC 4）。
 *
 * <p><b>与支付 SPI {@link PayCallbackHandler} 平行而非共用</b>（强约束 #4 / README R2）：本接口仅服务
 * <b>退款成功回调</b>，由 {@link RefundCallbackDispatcher} 按业务类型分发，<b>独立 key 空间</b>
 * （callback_log.callback_type=refund）—— 不与支付回调 SPI 混用，避免路由 key 撞。各业务模块
 * （ORD / GACHA）实现本接口并注册为 Spring Bean，新业务接入<b>零改动 PAY-103</b>（无 switch-case）。</p>
 *
 * <p><b>路由 key</b>：取原支付交易行的 {@link GzPayTransaction#getBusinessType() business_type}
 * （{@code preorder} / {@code gacha}），与支付 SPI 同取值但不同 dispatcher。</p>
 *
 * <p><b>事务边界</b>：{@link #onRefunded} 在 PAY-103 退款回调的同一事务内（REQUIRED 传播）被调用 ——
 * 业务方在此把自己的业务订单状态推进到 refunded（如 {@code gz_ord_order.paid → refunded}，
 * doc/10 §6.N10 / 字段表 §4.5）。<b>handler 抛异常 → 整笔回调事务回滚</b>（退款单保 refunding）
 * → callback 返回失败让微信重试 / admin 人工介入。因此 handler 内<b>只更新业务订单状态</b>。</p>
 *
 * <p><b>退款回滚业务侧约束</b>（doc/10 §6.N10 / §7.N13）：预购特例退款 SKU 库存<b>不归还</b>（已采购）；
 * 扭蛋退款由其 handler 决策。本 SPI 只保证 PAY 层动作 + 分发正确，业务回滚逻辑留各业务域 ticket。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-PAY-103)
 */
public interface IRefundCallbackHandler {

    /**
     * 本 handler 负责的业务类型（路由 key）。
     *
     * @return business_type 取值，如 {@code preorder} / {@code gacha}
     *         （取 {@link org.dromara.gz.common.pay.enums.PayBusinessType} 常量，不硬编码字面量）
     */
    String supportedBusinessType();

    /**
     * 退款成功业务回调（doc/10 §6.N10）。在 PAY-103 退款回调事务内、退款单已置 refunded 后被调用。
     *
     * <p>业务方在此把自己的业务订单推进到 refunded（如 {@code gz_ord_order} paid → refunded）。
     * 抛异常 → 整笔退款回调事务回滚（含退款单 refunded 推进）→ 微信重试 / admin 人工介入。</p>
     *
     * @param refund 已置 refunded 的退款单（含 refund_no / out_trade_no / transaction_id / refund_amount_cent）
     * @param txn    原支付交易行（已置 refunded，业务方按 business_order_no 定位自己的业务订单）
     */
    void onRefunded(GzPayRefund refund, GzPayTransaction txn);
}
