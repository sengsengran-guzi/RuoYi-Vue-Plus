package org.dromara.gz.jp.service.callback;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.gz.common.pay.domain.entity.GzPayRefund;
import org.dromara.gz.common.pay.domain.entity.GzPayTransaction;
import org.dromara.gz.common.pay.enums.PayBusinessType;
import org.dromara.gz.common.pay.service.spi.IRefundCallbackHandler;
import org.dromara.gz.jp.service.IGzJpRefundService;
import org.springframework.stereotype.Component;

/**
 * 拼团<b>全额退款</b>回调 handler（GZ-JP-107，实现 GZ-PAY-103 退款 SPI）。
 *
 * <p><b>★ 这不是拼团退款的主路径</b>。拼团的行级退款走的是自己的入口
 * （{@code GzJpRefundServiceImpl} + {@code /api/gz/jp/pay/refund-notify}，退款单在
 * {@code gz_jp_refund}），<b>压根不经过 {@code PayRefundServiceImpl}</b>，
 * 因此也永远不会触发本 handler。</p>
 *
 * <p><b>那它是干什么的</b>：堵一个真实存在的洞 —— admin 在<b>支付管理</b>页可以对<b>任意</b>
 * {@code gz_pay_transaction} 发起全额退款，包括 {@code business_type='jp'} 的那些。
 * 那条链路完全不认识拼团订单：钱整单退了，而 {@code gz_jp_order} 还是「已支付」、
 * 每一行都没有退款信息 —— 客人与店员看到的是两套互相矛盾的事实。
 * 注册本 handler 后，那种操作至少能把拼团侧同步过来。</p>
 *
 * <p><b>事务边界</b>：{@link #onRefunded} 在 PAY-103 退款回调事务内（REQUIRED）被调用，
 * 此时退款单与交易行都已置 refunded。抛异常 → 整笔回调事务回滚 → 微信重试。
 * 所以这里<b>只改自己的业务状态</b>，不发起新的外部调用。</p>
 *
 * <p><b>刻意不碰 {@code fulfill_status}</b>：钱退了不等于货没买到（ADR-0007 双状态机正交）。
 * 把已发货的行改成「购买失败」会让客人看到自己收到的东西显示为没买到。</p>
 *
 * <p><b>⚠️ 已知风险（无法在 jp 域内根治，见 report §风险）</b>：拼团行级退款不写
 * {@code gz_pay_refund}，所以 {@code PayRefundTxService.createRefunding} 的
 * 「一笔支付只允许一条退款单」闸门<b>看不见</b>它们 —— admin 理论上能在已部分退款的 jp 单上再发起全额退款。
 * 真提交时微信会因「退款总额超过原单」拒绝（钱是安全的），但会留下一条 failed 的 gz_pay_refund。
 * 根治要动 {@code PayRefundTxService}，那是本卡明令不碰的线上资金链路 ——
 * 缓解措施是 admin 侧不要对 {@code business_type='jp'} 的交易用支付管理页退款。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-JP-107)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JpRefundCallbackHandler implements IRefundCallbackHandler {

    private final IGzJpRefundService refundService;

    @Override
    public String supportedBusinessType() {
        return PayBusinessType.JP;
    }

    @Override
    public void onRefunded(GzPayRefund refund, GzPayTransaction txn) {
        log.warn("[gz-jp] ★ 拼团订单被 GZ-PAY 全额退款（非行级退款路径）refund_no={} out_trade_no={} "
                + "business_order_no={} amount={} —— 同步拼团侧订单与商品行",
            refund.getRefundNo(), refund.getOutTradeNo(), txn.getBusinessOrderNo(),
            refund.getRefundAmountCent());
        refundService.onFullRefundedByPayDomain(txn.getBusinessOrderNo(), refund.getOutTradeNo());
    }
}
