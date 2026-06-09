package org.dromara.gz.gacha.service.callback;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.gz.common.pay.domain.entity.GzPayTransaction;
import org.dromara.gz.common.pay.enums.PayBusinessType;
import org.dromara.gz.common.pay.service.spi.PayCallbackHandler;
import org.dromara.gz.gacha.service.IGzGachaDrawService;
import org.springframework.stereotype.Component;

/**
 * 扭蛋（业务线 B）支付成功回调 handler（GZ-GACHA-104 AC3，实现 PAY-101 支付 SPI）。
 *
 * <p><b>架构：处理器下沉 gz-gacha</b>（强约束 — gz-common 不可依赖 gz-gacha = 循环；
 * {@code PayCallbackDispatcher} 运行时按 {@code supportedBusinessType()='gacha'} 自动收集本 Bean，
 * gz-common 不加占位 handler）。</p>
 *
 * <p><b>事务边界</b>（PAY-101 SPI 契约 / 决策 D2）：{@link #onPaid} 在 PAY-101 {@code handlePaymentNotify}
 * 回调事务内（REQUIRED 传播）被调用，交易行已置 paid。本 handler <b>仅</b>触发 {@code @Async("gachaExecutor")}
 * 的开盒事务（{@code executeDrawTransaction} 走代理 → 入队独立线程池异步执行），<b>不在回调线程内跑开盒</b> ——
 * 微信 V3 回调要求 &lt; 3s 响应（决策 D2），开盒事务 SELECT FOR UPDATE + 多步 UPDATE 可能 &gt; 1s。</p>
 *
 * <p><b>幂等</b>：PAY-101 保证 {@code onPaid} 对同笔交易至多调一次；开盒事务自身再靠
 * {@code gz_gacha_draw.uk_tenant_pay_tx} DB 唯一索引兜底（微信重推 / @Async 重入，强约束 #3）。
 * <b>本 handler 不做退款</b>（扭蛋域无系统退款 —— 开盒失败走重抽改派，不触 gz_pay_refund）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-GACHA-104)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GachaPayCallbackHandler implements PayCallbackHandler {

    private final IGzGachaDrawService gachaDrawService;

    @Override
    public String supportedBusinessType() {
        return PayBusinessType.GACHA;
    }

    @Override
    public void onPaid(GzPayTransaction txn) {
        log.info("[gz-gacha] 扭蛋支付回调命中 out_trade_no={} userId={} transaction_id={} → 触发异步开盒事务",
            txn.getOutTradeNo(), txn.getUserId(), txn.getTransactionId());
        // 异步触发开盒（@Async 走代理 → gachaExecutor 线程池；回调线程立即返回不阻塞，决策 D2）。
        // payTransactionId = out_trade_no（gz_gacha_draw.pay_transaction_id 同口径，幂等关键）。
        gachaDrawService.executeDrawTransaction(txn.getOutTradeNo());
    }
}
