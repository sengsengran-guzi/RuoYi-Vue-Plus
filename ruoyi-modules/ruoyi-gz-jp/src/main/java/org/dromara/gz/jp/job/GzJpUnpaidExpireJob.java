package org.dromara.gz.jp.job;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.tenant.helper.TenantHelper;
import org.dromara.gz.common.pay.domain.entity.GzPayTransaction;
import org.dromara.gz.common.pay.enums.PayBusinessType;
import org.dromara.gz.common.pay.enums.PayStatus;
import org.dromara.gz.common.pay.mapper.GzPayTransactionMapper;
import org.dromara.gz.common.pay.service.IGzPayTransactionService;
import org.dromara.gz.common.pay.service.IGzPayTransactionService.ReconcileOutcome;
import org.dromara.gz.common.pay.service.internal.IWechatPayClient;
import org.dromara.gz.common.pay.service.internal.IWechatPayClient.QueryResult;
import org.dromara.gz.jp.domain.entity.GzJpOrder;
import org.dromara.gz.jp.mapper.GzJpOrderMapper;
import org.dromara.gz.jp.service.internal.GzJpUnpaidExpireTxService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 拼团 / 代切订单<b>未付款超时关单</b>（GZ-JP-110，FLOW:F-JP-10.step1，REQ-ORDER-011）。
 *
 * <p><b>不是 SnailJob 的 {@code @JobExecutor}</b>（prod 没部署 SnailJob，那种壳从不触发）—— 本类是核心逻辑，
 * 由 {@link GzLocalJpScheduler}（Spring {@code @Scheduled}，开关 {@code gz.local-scheduler.enabled}）每 5 分钟调一次。</p>
 *
 * <p>扫 {@code business_status='created'} 且 {@code create_time} 早于阈值（默认 15 分钟，
 * {@code gz.jp.order.unpaid-expire-minutes}）的订单，<b>逐单</b>按固定顺序处理：</p>
 * <ol>
 *   <li><b>查单</b>（{@link IWechatPayClient#queryByOutTradeNo}）—— 微信侧其实已付款 → 按支付回调同一路径补推进成 paid，
 *       <b>不关</b>；本地支付单已 timeout / closed 却查到已付款（迟到付款）→ ERROR 日志，订单不动（交 GZ-PAY-106 的
 *       「迟到付款」人工处理）。</li>
 *   <li><b>关微信单</b>（{@link IWechatPayClient#closeOrder}）—— 正常情况下 GZ-PAY-106 的 {@code time_expire} 早已让它过期，
 *       这一步是兜底：覆盖 106 之前创建、没带过期时间的在途单。<b>关失败（含微信回「已支付」）→ 本轮跳过、下轮再试，
 *       本地不取消</b> —— 否则商品锁放给了别人，客人却还能把这单付掉。</li>
 *   <li><b>关本地单</b>（{@link GzJpUnpaidExpireTxService#cancelExpired}，独立事务的条件更新 {@code created→cancelled}）；
 *       affected=0 说明回调抢先，静默跳过。</li>
 * </ol>
 * <p>从未拉起过支付（没有带 prepay_id 的支付流水 —— 微信侧压根没这单）的直接走第 3 步。</p>
 *
 * <p><b>每单独立</b>：单单 try/catch，一单抛错只记 ERROR 计 failed，继续下一单；DB 写在
 * {@code REQUIRES_NEW} 事务里，查单 / 关单这两步外部调用不在任何事务内。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-JP-110)
 */
@Slf4j
@Component
public class GzJpUnpaidExpireJob {

    /** 未付款超时分钟数的默认值（与拼豆 15 分钟一致；支付单本身 5 分钟就在微信侧过期） */
    public static final int DEFAULT_UNPAID_EXPIRE_MINUTES = 15;

    /** 每轮最多处理几单（处理不完的下一轮接着扫） */
    static final int BATCH_LIMIT = 200;

    /** 微信 V3 trade_state：已支付 */
    static final String TRADE_SUCCESS = "SUCCESS";
    /** 微信 V3 trade_state：转入退款（说明曾经付过款） */
    static final String TRADE_REFUND = "REFUND";
    /** 微信 V3 trade_state：已关闭 */
    static final String TRADE_CLOSED = "CLOSED";
    /** 微信 V3 trade_state：已撤销 */
    static final String TRADE_REVOKED = "REVOKED";
    /** 微信 V3 trade_state：用户支付中 */
    static final String TRADE_USERPAYING = "USERPAYING";

    private final GzJpOrderMapper orderMapper;
    private final GzPayTransactionMapper payTransactionMapper;
    private final IWechatPayClient wechatPayClient;
    private final IGzPayTransactionService payTransactionService;
    private final GzJpUnpaidExpireTxService txService;
    private final int unpaidExpireMinutes;

    public GzJpUnpaidExpireJob(GzJpOrderMapper orderMapper,
                               GzPayTransactionMapper payTransactionMapper,
                               IWechatPayClient wechatPayClient,
                               IGzPayTransactionService payTransactionService,
                               GzJpUnpaidExpireTxService txService,
                               @Value("${gz.jp.order.unpaid-expire-minutes:" + DEFAULT_UNPAID_EXPIRE_MINUTES + "}")
                               int unpaidExpireMinutes) {
        this.orderMapper = orderMapper;
        this.payTransactionMapper = payTransactionMapper;
        this.wechatPayClient = wechatPayClient;
        this.payTransactionService = payTransactionService;
        this.txService = txService;
        this.unpaidExpireMinutes = unpaidExpireMinutes > 0 ? unpaidExpireMinutes : DEFAULT_UNPAID_EXPIRE_MINUTES;
    }

    /**
     * 跑一轮：扫超时未付款订单 → 逐单「查单 → 关微信单 → 关本地单」。
     *
     * <p>调度线程没有登录上下文 → {@code TenantHelper.ignore} 全租户扫。</p>
     *
     * @return 本轮统计
     */
    public ExpireResult run() {
        return TenantHelper.ignore(() -> {
            LocalDateTime now = LocalDateTime.now();
            LocalDateTime deadline = now.minusMinutes(unpaidExpireMinutes);
            List<GzJpOrder> orders = orderMapper.selectUnpaidExpired(deadline, BATCH_LIMIT);
            if (orders == null || orders.isEmpty()) {
                return new ExpireResult(0, 0, 0, 0, 0);
            }
            if (orders.size() >= BATCH_LIMIT) {
                log.warn("[gz-jp-unpaid-expire] 本轮超时未付款订单达到上限 {} 单，余下的下一轮处理", BATCH_LIMIT);
            }
            int cancelled = 0;
            int recoveredPaid = 0;
            int skipped = 0;
            int failed = 0;
            for (GzJpOrder order : orders) {
                try {
                    switch (expireOne(order)) {
                        case CANCELLED -> cancelled++;
                        case RECOVERED_PAID -> recoveredPaid++;
                        case SKIPPED -> skipped++;
                    }
                } catch (Exception ex) {
                    failed++;
                    log.error("[gz-jp-unpaid-expire] 关单异常 orderNo={} orderId={}，本单跳过、继续下一单",
                        order.getOrderNo(), order.getId(), ex);
                }
            }
            log.info("[gz-jp-unpaid-expire] 超时={}min scanned={} cancelled={} recoveredPaid={} skipped={} failed={}",
                unpaidExpireMinutes, orders.size(), cancelled, recoveredPaid, skipped, failed);
            return new ExpireResult(orders.size(), cancelled, recoveredPaid, skipped, failed);
        });
    }

    /**
     * 处理一单。顺序固定：查单 → 关微信单 → 关本地单；任一步没走通都不落本地 cancelled。
     */
    Outcome expireOne(GzJpOrder order) {
        String orderNo = order.getOrderNo();
        List<GzPayTransaction> txns = payTransactionMapper.selectList(Wrappers.<GzPayTransaction>lambdaQuery()
            .eq(GzPayTransaction::getBusinessType, PayBusinessType.JP)
            .eq(GzPayTransaction::getBusinessOrderNo, orderNo)
            .orderByAsc(GzPayTransaction::getId));
        if (txns == null) {
            txns = List.of();
        }

        // ── 本地流水已能说明「付过款」的，先处理，不碰微信 ──
        for (GzPayTransaction txn : txns) {
            if (PayStatus.PAID.equals(txn.getStatus())) {
                // 支付流水已 paid、订单却仍是 created：回调那笔事务里订单侧没推进成 → 重放 onPaid 补推进
                log.warn("[gz-jp-unpaid-expire] 支付流水已 paid 但订单仍 created，补推进 orderNo={} outTradeNo={}",
                    orderNo, txn.getOutTradeNo());
                txService.replayPaid(txn);
                return Outcome.RECOVERED_PAID;
            }
            if (PayStatus.REFUNDING.equals(txn.getStatus()) || PayStatus.REFUNDED.equals(txn.getStatus())) {
                log.error("[gz-jp-unpaid-expire] 订单仍 created 但支付流水已进入退款 orderNo={} outTradeNo={} status={}，"
                    + "不关单，需人工核对", orderNo, txn.getOutTradeNo(), txn.getStatus());
                return Outcome.SKIPPED;
            }
        }

        // ── ① 查单：只查微信侧真有这单的流水（拿到过 prepay_id）；全部查完再决定，任何一笔已付款都不关 ──
        List<GzPayTransaction> toClose = new ArrayList<>();
        for (GzPayTransaction txn : txns) {
            if (StrUtil.isBlank(txn.getPrepayId())) {
                // 统一下单没成功（微信侧没有这单）—— 不用查也不用关
                continue;
            }
            QueryResult q;
            try {
                q = wechatPayClient.queryByOutTradeNo(txn.getOutTradeNo());
            } catch (Exception ex) {
                log.warn("[gz-jp-unpaid-expire] 查单失败，本轮跳过、下轮再试 orderNo={} outTradeNo={}：{}",
                    orderNo, txn.getOutTradeNo(), ex.getMessage(), ex);
                return Outcome.SKIPPED;
            }
            String tradeState = q == null ? null : q.tradeState();
            if (TRADE_SUCCESS.equals(tradeState)) {
                return onWechatPaid(order, txn, q);
            }
            if (TRADE_REFUND.equals(tradeState)) {
                log.error("[gz-jp-unpaid-expire] 微信侧显示该支付单已转入退款（曾付款）但订单仍 created orderNo={} outTradeNo={}，"
                    + "不关单，需人工核对", orderNo, txn.getOutTradeNo());
                return Outcome.SKIPPED;
            }
            if (TRADE_USERPAYING.equals(tradeState)) {
                log.warn("[gz-jp-unpaid-expire] 客人正在支付中，本轮不关 orderNo={} outTradeNo={}", orderNo, txn.getOutTradeNo());
                return Outcome.SKIPPED;
            }
            if (TRADE_CLOSED.equals(tradeState) || TRADE_REVOKED.equals(tradeState)) {
                // 微信侧已关（time_expire 到点 / GZ-PAY 超时任务已关）—— 目的已达成，不用再关
                continue;
            }
            // NOTPAY / PAYERROR / 其他未成交态 → 需要关掉
            toClose.add(txn);
        }

        // ── ② 关微信单：关失败（含微信回「已支付」）→ 本轮跳过，本地不取消 ──
        for (GzPayTransaction txn : toClose) {
            try {
                wechatPayClient.closeOrder(txn.getOutTradeNo());
            } catch (Exception ex) {
                log.warn("[gz-jp-unpaid-expire] 关微信支付单失败，本地不取消、下轮再试 orderNo={} outTradeNo={}：{}",
                    orderNo, txn.getOutTradeNo(), ex.getMessage(), ex);
                return Outcome.SKIPPED;
            }
        }

        // ── ③ 关本地单（条件更新 created→cancelled；affected=0 = 回调抢先）──
        if (txService.cancelExpired(order.getId(), LocalDateTime.now())) {
            log.info("[gz-jp-unpaid-expire] 超时未付款已关单 orderNo={} orderId={} 关微信单={} 笔",
                orderNo, order.getId(), toClose.size());
            return Outcome.CANCELLED;
        }
        log.info("[gz-jp-unpaid-expire] 关单时订单已非 created（支付回调抢先），跳过 orderNo={}", orderNo);
        return Outcome.SKIPPED;
    }

    /**
     * 查单发现微信侧已付款。
     *
     * <ul>
     *   <li>本地流水仍 pending → 回调丢了：走 GZ-PAY 的补单单点 {@code reconcilePaid}（与回调同一路径：
     *       流水 paid + SPI 分发 → {@code JpPayCallbackHandler} → {@code IGzJpOrderService.onPaid}，幂等）。</li>
     *   <li>本地流水已 timeout / closed / failed → 迟到付款：ERROR 日志，订单不关、不释放锁，交 GZ-PAY-106 的
     *       「迟到付款」人工处理（106 的查单任务负责记 callback_log late_paid，这里不重复记）。</li>
     * </ul>
     */
    private Outcome onWechatPaid(GzJpOrder order, GzPayTransaction txn, QueryResult q) {
        if (!PayStatus.PENDING.equals(txn.getStatus())) {
            log.error("[gz-jp-unpaid-expire] 迟到付款：微信侧已付款但本地支付流水为 {}，订单保持 created、不关单 "
                    + "orderNo={} outTradeNo={} transactionId={} —— 需人工原路退款或补单",
                txn.getStatus(), order.getOrderNo(), txn.getOutTradeNo(), q.transactionId());
            return Outcome.SKIPPED;
        }
        ReconcileOutcome outcome = payTransactionService.reconcilePaid(txn.getId(), q.transactionId(), null, q.rawBody());
        log.info("[gz-jp-unpaid-expire] 微信侧已付款（回调未到），补推进 orderNo={} outTradeNo={} outcome={}",
            order.getOrderNo(), txn.getOutTradeNo(), outcome);
        return outcome == ReconcileOutcome.PAID ? Outcome.RECOVERED_PAID : Outcome.SKIPPED;
    }

    /** 单单处理结果。 */
    enum Outcome {
        /** 已关微信单（或无需关）+ 本地 created→cancelled */
        CANCELLED,
        /** 微信侧 / 本地流水其实已付款 → 补推进成 paid，不关 */
        RECOVERED_PAID,
        /** 本轮不动（查单 / 关单失败、迟到付款、支付中、回调抢先），下轮再看 */
        SKIPPED
    }

    /**
     * 一轮统计。
     *
     * @param scanned       扫到的超时未付款订单数
     * @param cancelled     本轮关单数
     * @param recoveredPaid 发现其实已付款、补推进成 paid 的单数
     * @param skipped       本轮不动的单数
     * @param failed        处理抛异常的单数
     */
    public record ExpireResult(int scanned, int cancelled, int recoveredPaid, int skipped, int failed) {
    }
}
