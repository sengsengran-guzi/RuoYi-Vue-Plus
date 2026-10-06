package org.dromara.gz.jp.service.internal;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.gz.common.pay.domain.entity.GzPayTransaction;
import org.dromara.gz.jp.mapper.GzJpOrderMapper;
import org.dromara.gz.jp.service.IGzJpOrderService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 未付款超时关单的<b>单单事务</b>（GZ-JP-110，FLOW:F-JP-10.step1 ③）。
 *
 * <p><b>为什么单独成一个 bean</b>：调度逐单处理，「一单一个事务、一单失败不连累其他单」要靠
 * {@code REQUIRES_NEW} —— 而 Spring 事务走代理，同一个类里自调用不生效，所以事务边界必须放在
 * 另一个 bean 上。查单 / 关微信单这两步是<b>外部调用</b>，刻意不在任何 DB 事务里（不让网络等待占着行锁）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-JP-110)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GzJpUnpaidExpireTxService {

    private final GzJpOrderMapper orderMapper;
    private final IGzJpOrderService orderService;
    private final GzJpSnapLockService snapLocks;

    /**
     * 本地关单：{@code created → cancelled} + {@code cancelled_time}（条件更新）。
     *
     * <p><b>只能在「微信侧支付单已关 / 从未拉起过支付」之后调</b> —— 顺序由调用方保证。
     * affected=0 说明支付回调抢先把单推成了 paid（或已被取消），按「无事可做」处理，不是错误。</p>
     *
     * <p><b>代切商品锁的释放挂在这里</b>（GZ-SNAP-304）：必须与 {@code markCancelled} 同一事务，
     * 且只在本方法返回 true（本次真的关了单）时释放 —— affected=0 时订单可能已是 paid，锁不能放。</p>
     *
     * @param orderId       订单 id
     * @param cancelledTime 取消时间
     * @return true = 本次关单成功；false = 已非 created（回调抢先），静默跳过
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public boolean cancelExpired(Long orderId, LocalDateTime cancelledTime) {
        snapLocks.lockOrderItemsForCancellation(orderId);
        boolean cancelled = orderMapper.markCancelled(orderId, cancelledTime) == 1;
        if (cancelled) snapLocks.releaseOrder(orderId);
        return cancelled;
    }

    /**
     * 补推进：本地支付单已是 paid、订单却还停在 created（回调那笔事务里订单侧没推进成）→
     * 重放一次与支付回调完全相同的 {@link IGzJpOrderService#onPaid}。
     *
     * <p>{@code onPaid} 自带两道幂等守卫（订单 {@code WHERE business_status='created'} +
     * 商品行 {@code WHERE fulfill_status='purchasing'}），重放安全。它自身不开事务（原本跑在回调事务里），
     * 这里给它包一个独立事务，保证「订单 paid + 全部商品行进入履约起点」原子落库。</p>
     *
     * @param txn 本地已 paid 的支付流水（business_order_no = 订单号）
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void replayPaid(GzPayTransaction txn) {
        orderService.onPaid(txn);
    }
}
