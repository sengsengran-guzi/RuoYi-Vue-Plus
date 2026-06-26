package org.dromara.gz.common.pay.service.spi;

import org.dromara.gz.common.pay.domain.entity.GzPayTransaction;
import org.dromara.gz.common.pay.shipping.ShippingInfo;

import java.util.Optional;

/**
 * 支付成功回调业务分发 SPI（GZ-PAY-101 AC 4）。
 *
 * <p><b>路由真源</b>：支付回调按 {@link GzPayTransaction#getBusinessType() business_type} 分发到对应业务
 * handler。各业务模块（ORD / GACHA）实现本接口并注册为 Spring Bean，{@link PayCallbackDispatcher}
 * 按 {@link #supportedBusinessType()} 收集成 {@code Map<String, PayCallbackHandler>} —— 新业务接入
 * <b>零改动 PAY-101</b>（无 switch-case，无硬编码业务分支）。</p>
 *
 * <p><b>事务边界</b>（GZ-PAY-101 决策 D4 / R4）：{@link #onPaid} 在 PAY-101
 * {@code handlePaymentNotify} 的同一事务内（REQUIRED 传播）被调用 —— 业务方在此更新自己的业务订单
 * 状态（如 gz_ord_order.created → paid）。<b>handler 抛异常 → 整笔回调事务回滚</b>（交易行保
 * pending，不变 paid）→ callback 返回失败让微信重试 + PAY-102 主动查单兜底（doc/10 §6.E2）。
 * 因此 handler 内<b>只更新业务订单状态，不再触发新支付 / 不调外部不可回滚操作</b>。</p>
 *
 * <p><b>幂等</b>：PAY-101 已保证 {@code onPaid} 对同一笔交易<b>至多调一次</b>（transaction_id 去重 +
 * version 乐观锁两道防线，doc/10 §6.E1）。handler 实现方无需自己再做支付幂等，但更新业务订单时
 * 建议带状态守卫（如 {@code WHERE status='created'}）防御性兜底。</p>
 *
 * <p><b>key 空间约定</b>（R3）：本 SPI 仅服务<b>支付成功回调</b>，路由 key = business_type
 * （preorder / gacha / ...）。退款回调（PAY-103）走<b>独立 SPI key 空间</b>
 * （callback_log.callback_type=refund），不与本接口混用，避免路由 key 撞。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-PAY-101)
 */
public interface PayCallbackHandler {

    /**
     * 本 handler 负责的业务类型（路由 key）。
     *
     * @return business_type 取值，如 {@code preorder} / {@code gacha}
     *         （取 {@link org.dromara.gz.common.pay.enums.PayBusinessType} 常量，不要硬编码字面量）
     */
    String supportedBusinessType();

    /**
     * 支付成功业务回调（doc/10 §6.N6）。在 PAY-101 回调事务内、交易行已置 paid 后被调用。
     *
     * <p>业务方在此把自己的业务订单推进到 paid（如 {@code gz_ord_order} created → paid）。
     * 抛异常 → 整笔回调事务回滚（含交易行 paid 推进）→ 微信重试 + PAY-102 补单兜底。</p>
     *
     * @param txn 已置 paid 的支付交易行（含 out_trade_no / business_order_no / business_type /
     *            transaction_id / amount_cent / user_id / openid —— 业务方按 business_order_no
     *            定位自己的业务订单）
     */
    void onPaid(GzPayTransaction txn);

    /**
     * 构造本笔交易的微信「订单中心」发货信息（upload_shipping_info，消除支付完成页「未接入购物订单」提示）。
     *
     * <p>默认返回 {@link Optional#empty()} = 不上报发货信息（test 单 / 暂未接入订单中心的业务）。需要接入
     * 的业务（如拼豆）override 返回 {@link ShippingInfo}（虚拟商品 = {@link ShippingInfo#virtual}）。返回值
     * 由 PAY-101 在交易 paid 后落 {@code gz_pay_shipping_order} + 异步上报，<b>上报失败不回滚支付</b>。</p>
     *
     * @param txn 已置 paid 的支付交易行
     * @return 发货信息；空 = 本业务不接入订单中心
     */
    default Optional<ShippingInfo> buildShippingInfo(GzPayTransaction txn) {
        return Optional.empty();
    }
}
