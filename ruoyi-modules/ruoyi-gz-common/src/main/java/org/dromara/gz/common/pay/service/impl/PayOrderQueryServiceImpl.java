package org.dromara.gz.common.pay.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.tenant.helper.TenantHelper;
import org.dromara.gz.common.pay.domain.entity.GzPayTransaction;
import org.dromara.gz.common.pay.enums.PayStatus;
import org.dromara.gz.common.pay.mapper.GzPayTransactionMapper;
import org.dromara.gz.common.pay.service.IGzPayTransactionService;
import org.dromara.gz.common.pay.service.IGzPayTransactionService.ReconcileOutcome;
import org.dromara.gz.common.pay.service.IPayOrderQueryService;
import org.dromara.gz.common.pay.service.internal.IWechatPayClient;
import org.dromara.gz.common.pay.service.internal.IWechatPayClient.QueryResult;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

/**
 * 主动查单 + 补单服务实现（GZ-PAY-102，doc/10 §6.N7 / §6.E3）。
 *
 * <p><b>幂等补单单点</b>（强约束 #1 / 决策 D4）：{@code trade_state=SUCCESS} 一律走
 * {@link IGzPayTransactionService#reconcilePaid}（与被动回调相同的 SELECT FOR UPDATE + markPaid 乐观锁
 * + SPI 路由 + callback_log），<b>本类绝不另写 paid 推进逻辑</b>。其余状态（NOTPAY/USERPAYING/CLOSED/
 * REVOKED/PAYERROR）由本类按条件 UPDATE 推进 closed / failed / timeout，不涉及 SPI / 补单核心。</p>
 *
 * <p><b>事务边界</b>：本扫描循环不开外层事务（避免一条 handler 异常回滚整批）。补单经
 * {@code transactionService.reconcilePaid}（跨 bean 调用，各笔独立事务）；关单 / 失败 / timeout 是单条
 * 原子条件 UPDATE（自提交）。单条异常隔离（风险 R2：一条坏单不卡死整批，下轮重试）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-PAY-102)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PayOrderQueryServiceImpl implements IPayOrderQueryService {

    /** 主动查单兜底：超 5min 未到终态才查（避免和正常回调抢，doc/10 §6.N7） */
    private static final int RECONCILE_AFTER_MINUTES = 5;
    /** 24h 窗口：超 24h 老 pending 不查（用户已离场，由 30min 关单逻辑兜底），防无限堆积（强约束 #3） */
    private static final int RECONCILE_WINDOW_HOURS = 24;
    /** 单轮上限：控制在微信 V3 查单配额 + 执行器超时内（决策 D2，防雪崩） */
    private static final int SCAN_LIMIT = 100;
    /** 超 30min 仍 pending 且 NOTPAY → 关单防偷付（AC 8） */
    private static final int CLOSE_AFTER_MINUTES = 30;

    private final GzPayTransactionMapper transactionMapper;
    private final IWechatPayClient wechatPayClient;
    private final IGzPayTransactionService transactionService;

    @Override
    public ReconcileResult scanAndReconcile() {
        // cron 无登录态 → 全租户扫（与 expireTimeoutOrders / markNoShowBatch 同思路）
        return TenantHelper.ignore(() -> {
            LocalDateTime now = LocalDateTime.now();
            LocalDateTime windowStart = now.minusHours(RECONCILE_WINDOW_HOURS);
            LocalDateTime windowEnd = now.minusMinutes(RECONCILE_AFTER_MINUTES);
            List<Long> ids = transactionMapper.selectReconcilePendingIds(windowStart, windowEnd, SCAN_LIMIT);

            int paid = 0;
            int closed = 0;
            int failed = 0;
            int timeout = 0;
            int pending = 0;
            int skipped = 0;
            for (Long id : ids) {
                try {
                    Outcome outcome = reconcileOne(id, now);
                    switch (outcome) {
                        case PAID -> paid++;
                        case CLOSED -> closed++;
                        case FAILED -> failed++;
                        case TIMEOUT -> timeout++;
                        case KEEP_PENDING -> pending++;
                        case SKIPPED -> skipped++;
                    }
                } catch (Exception e) {
                    // 单条异常隔离（风险 R2：handler 异常 / 查单网络异常 → 本笔保持 pending，下轮重试）
                    skipped++;
                    log.error("[gz-pay] 主动查单单条失败 id={}", id, e);
                }
            }
            ReconcileResult result = new ReconcileResult(ids.size(), paid, closed, failed, timeout, pending, skipped);
            if (!ids.isEmpty()) {
                log.info("[gz-pay] 主动查单完成：扫描 {} / 补单 {} / 关闭 {} / 失败 {} / 超时关单 {} / 保持 pending {} / 跳过 {}",
                    result.scanned(), result.paid(), result.closed(), result.failed(),
                    result.timeout(), result.pending(), result.skipped());
            }
            return result;
        });
    }

    /**
     * 单笔查单 + 分发（AC 3-8）。先取轻量行拿 create_time / out_trade_no → 调微信查单 → 按 trade_state 分发。
     *
     * <p>状态推进幂等：补单经 reconcilePaid（行锁），关单 / 失败 / timeout 经条件 UPDATE（status='pending' 守卫）。</p>
     */
    private Outcome reconcileOne(Long id, LocalDateTime now) {
        GzPayTransaction tx = transactionMapper.selectById(id);
        if (tx == null || !PayStatus.PENDING.equals(tx.getStatus())) {
            // 扫描到推进间被并发回调改了 → 跳过（幂等 AC 5）
            return Outcome.SKIPPED;
        }
        String outTradeNo = tx.getOutTradeNo();

        // AC 3：调微信 V3 查单（按 out_trade_no）解析 trade_state
        QueryResult qr = wechatPayClient.queryByOutTradeNo(outTradeNo);
        String tradeState = qr.tradeState() == null ? "" : qr.tradeState().toUpperCase();

        switch (tradeState) {
            case "SUCCESS" -> {
                // AC 4：走 PAY-101 回调同一幂等补单路径（行锁 + markPaid 乐观锁 + SPI + callback_log）
                ReconcileOutcome r = transactionService.reconcilePaid(id, qr.transactionId(), qr.feeCent(), qr.rawBody());
                // 已终态（并发回调先推进）→ 视为幂等跳过，不计入 paid（AC 5）
                return r == ReconcileOutcome.PAID ? Outcome.PAID : Outcome.SKIPPED;
            }
            case "NOTPAY", "USERPAYING" -> {
                // AC 6：未支付 / 支付中 → 保持 pending 等下轮。但 NOTPAY 超 30min → 关单防偷付（AC 8）
                if ("NOTPAY".equals(tradeState) && isOverCloseThreshold(tx, now)) {
                    return closeAndTimeout(id, outTradeNo);
                }
                return Outcome.KEEP_PENDING;
            }
            case "CLOSED", "REVOKED" -> {
                // AC 7：微信侧已关闭 / 已撤销 → 本地 closed + closed_time
                int affected = transactionMapper.markClosed(id, now);
                return affected == 1 ? Outcome.CLOSED : Outcome.SKIPPED;
            }
            case "PAYERROR" -> {
                // AC 7：支付失败 → 本地 failed（不写 closed_time，非关单语义）
                int affected = transactionMapper.markFailed(id);
                return affected == 1 ? Outcome.FAILED : Outcome.SKIPPED;
            }
            default -> {
                // 未知 / REFUND 等非预期态 → 保守保持 pending，记日志待人工核对（不臆断推进，CLAUDE.md §6 #7）
                log.warn("[gz-pay] 主动查单返回非预期 trade_state={} out_trade_no={}，保持 pending", tradeState, outTradeNo);
                return Outcome.KEEP_PENDING;
            }
        }
    }

    /**
     * 超 30min 仍 pending 且 NOTPAY → 调微信 V3 关单 + 推进 timeout（AC 8 防偷付）。
     *
     * <p>先调微信 closeOrder（幂等，微信侧已关 / 已支付按 SDK 行为）再 markTimeout（status='pending' 守卫）。
     * closeOrder 成功但 markTimeout affected=0（并发已变）→ SKIPPED；关单异常向上抛由单条隔离捕获下轮重试。</p>
     */
    private Outcome closeAndTimeout(Long id, String outTradeNo) {
        wechatPayClient.closeOrder(outTradeNo);
        int affected = transactionMapper.markTimeout(id, LocalDateTime.now());
        if (affected == 1) {
            log.info("[gz-pay] 超 30min 未付主动关单 out_trade_no={} → timeout", outTradeNo);
            return Outcome.TIMEOUT;
        }
        return Outcome.SKIPPED;
    }

    /**
     * 判定交易是否超过关单阈值（create_time + 30min &lt; now）。create_time 为 {@code java.util.Date}（BaseEntity）。
     */
    private boolean isOverCloseThreshold(GzPayTransaction tx, LocalDateTime now) {
        if (tx.getCreateTime() == null) {
            return false;
        }
        LocalDateTime createTime = LocalDateTime.ofInstant(tx.getCreateTime().toInstant(), ZoneId.systemDefault());
        return createTime.plusMinutes(CLOSE_AFTER_MINUTES).isBefore(now);
    }

    /**
     * 单笔分发结果（内部用，对外聚合成 {@link ReconcileResult}）。
     */
    private enum Outcome {
        PAID, CLOSED, FAILED, TIMEOUT, KEEP_PENDING, SKIPPED
    }
}
