package org.dromara.gz.common.pay.service;

/**
 * 主动查单 + 补单服务（GZ-PAY-102，doc/10 §6.N7 主动查单兜底 / §6.E3 回调丢失自愈）。
 *
 * <p>SnailJob 每分钟扫 {@code gz_pay_transaction} 中 {@code status='pending'} 且超 5min 未到终态的交易
 * （24h 窗口 + LIMIT 100 防雪崩），逐条调微信 V3 查单核对真实 {@code trade_state}：</p>
 * <ul>
 *   <li>{@code SUCCESS} → 走<b>与被动回调完全相同的幂等补单单点</b>
 *       （{@link IGzPayTransactionService#reconcilePaid}，强约束 #1 单点维护）</li>
 *   <li>{@code NOTPAY} / {@code USERPAYING} → 保持 pending 等下轮（超 30min NOTPAY → 关单 + timeout）</li>
 *   <li>{@code CLOSED} / {@code REVOKED} → 本地 closed；{@code PAYERROR} → 本地 failed</li>
 * </ul>
 *
 * <p>核心可测逻辑全在本 service，{@code PayOrderQueryJobExecutor} 仅触发壳（脱离 SnailJob server 单测）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-PAY-102)
 */
public interface IPayOrderQueryService {

    /**
     * 扫描 pending 交易 → 逐条查单 → 补单 / 关单 / 失败推进（AC 2-8）。
     *
     * <p>cron 无登录态 → {@code TenantHelper.ignore} 全租户扫。单条异常隔离（一条坏单不卡死整批）。</p>
     *
     * @return 本轮对账统计
     */
    ReconcileResult scanAndReconcile();

    /**
     * 主动查单对账统计（AC 9 单测断言用）。
     *
     * @param scanned   扫描的 pending 单数
     * @param paid      查到 SUCCESS 补单成功数（推进 paid + SPI 分发）
     * @param closed    查到 CLOSED / REVOKED 推进 closed 数
     * @param failed    查到 PAYERROR 推进 failed 数
     * @param timeout   超 30min NOTPAY 关单 + timeout 数
     * @param pending   仍保持 pending 数（NOTPAY &lt; 30min / USERPAYING）
     * @param skipped   单条异常 / 已终态 / 行不存在跳过数
     */
    record ReconcileResult(int scanned, int paid, int closed, int failed, int timeout, int pending, int skipped) {
    }
}
