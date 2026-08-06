package org.dromara.gz.jp.service.callback;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.gz.common.pay.domain.entity.GzPayTransaction;
import org.dromara.gz.common.pay.enums.PayBusinessType;
import org.dromara.gz.common.pay.service.spi.PayCallbackHandler;
import org.dromara.gz.jp.service.IGzJpOrderService;
import org.springframework.stereotype.Component;

/**
 * 拼团支付成功回调 handler（GZ-JP-105，实现 GZ-PAY-101 支付 SPI，FLOW:F-JP-02.step6）。
 *
 * <p><b>架构：处理器下沉本模块</b>（照 {@code PreorderPayCallbackHandler} 范式）——
 * gz-common <b>不能依赖</b> gz-jp（gz-jp→gz-common 是单向的，反向即循环依赖），
 * 所以业务推进逻辑必须在 ruoyi-gz-jp 实现本接口，由 {@code PayCallbackDispatcher}
 * 在启动期按 {@link #supportedBusinessType()} 收进路由表。<b>GZ-PAY 侧零改动</b>（无 switch-case）。</p>
 *
 * <p><b>事务边界</b>：{@link #onPaid} 在 GZ-PAY {@code handlePaymentNotify} 的回调事务内
 * （REQUIRED 传播）被调用，此时交易行已置 paid。本 handler 委托
 * {@link IGzJpOrderService#onPaid} 把 {@code gz_jp_order}（business_order_no = order_no）
 * created → paid 并激活全部商品行（同事务）。抛异常 → 整笔回调事务回滚（交易行退回 pending）
 * → 微信重试 + GZ-PAY 主动查单兜底。</p>
 *
 * <p><b>幂等</b>：GZ-PAY 侧已保证同一笔交易至多调一次（transaction_id 去重 + 乐观锁）；
 * 本域再加两道守卫（订单 {@code WHERE business_status='created'} + 商品行
 * {@code WHERE fulfill_status='purchasing'}），重放不会重复推进。</p>
 *
 * <p><b>{@code buildShippingInfo} 刻意不 override</b>（保持默认「不上报」）：微信「订单中心」
 * 发货信息上报是<b>实物商品</b>的强制项，而拼团的到货周期是几周到几个月、发货由店员手工填顺丰单号
 * （GZ-JP-106）—— 支付当下没有任何可上报的发货事实。硬报会误导微信侧的收货判定。
 * 拼团的发货上报若要接，应在<b>置 delivered 那一刻</b>接（GZ-JP-106 的事，且需甲方确认商户号侧要求），
 * 不是在支付回调里。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-JP-105)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JpPayCallbackHandler implements PayCallbackHandler {

    private final IGzJpOrderService orderService;

    @Override
    public String supportedBusinessType() {
        return PayBusinessType.JP;
    }

    @Override
    public void onPaid(GzPayTransaction txn) {
        log.info("[gz-jp] 拼团支付回调命中 out_trade_no={} business_order_no={} transaction_id={} amount={}",
            txn.getOutTradeNo(), txn.getBusinessOrderNo(), txn.getTransactionId(), txn.getAmountCent());
        orderService.onPaid(txn);
    }
}
