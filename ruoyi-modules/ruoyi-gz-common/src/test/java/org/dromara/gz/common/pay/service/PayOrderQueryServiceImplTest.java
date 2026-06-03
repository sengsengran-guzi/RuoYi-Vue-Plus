package org.dromara.gz.common.pay.service;

import org.dromara.common.tenant.helper.TenantHelper;
import org.dromara.gz.common.pay.domain.entity.GzPayTransaction;
import org.dromara.gz.common.pay.enums.PayStatus;
import org.dromara.gz.common.pay.mapper.GzPayTransactionMapper;
import org.dromara.gz.common.pay.service.IGzPayTransactionService.ReconcileOutcome;
import org.dromara.gz.common.pay.service.IPayOrderQueryService.ReconcileResult;
import org.dromara.gz.common.pay.service.impl.PayOrderQueryServiceImpl;
import org.dromara.gz.common.pay.service.internal.IWechatPayClient;
import org.dromara.gz.common.pay.service.internal.IWechatPayClient.QueryResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link PayOrderQueryServiceImpl} 单测（GZ-PAY-102 AC 9，≥ 5 个测试方法，全 mock）。
 *
 * <p>覆盖 doc/10 §6.N7 主动查单兜底全分支（mock {@link IWechatPayClient}）：</p>
 * <ol>
 *   <li>SUCCESS → reconcilePaid 被调 → 补单计数 +1（AC 4，走 PAY-101 同一幂等路径）</li>
 *   <li>同笔重复扫 → 第二轮 reconcilePaid 返 SKIPPED_TERMINAL → 补单仅计一次（AC 5 幂等）</li>
 *   <li>NOTPAY 且 &lt; 30min → 保持 pending、不关单、不补单（AC 6）</li>
 *   <li>NOTPAY 且 &gt; 30min → closeOrder + markTimeout（AC 8 防偷付）</li>
 *   <li>CLOSED → markClosed（AC 7）；PAYERROR → markFailed（AC 7）；USERPAYING → 保持 pending（AC 6）</li>
 * </ol>
 *
 * <p><b>幂等单点</b>：补单（SUCCESS）一律委托 {@code transactionService.reconcilePaid}（PAY-101 补单核心），
 * 本类不另写 paid 推进 —— 单测断言「reconcilePaid 被调」+「never markClosed/markFailed/markTimeout 于 SUCCESS 分支」。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-PAY-102)
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class PayOrderQueryServiceImplTest {

    @Mock
    private GzPayTransactionMapper transactionMapper;

    @Mock
    private IWechatPayClient wechatPayClient;

    @Mock
    private IGzPayTransactionService transactionService;

    private PayOrderQueryServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new PayOrderQueryServiceImpl(transactionMapper, wechatPayClient, transactionService);
    }

    /** 构造一条 pending 交易（create_time = now - minutesAgo 分钟）。 */
    private GzPayTransaction pendingTx(long id, String outTradeNo, int minutesAgo) {
        GzPayTransaction tx = new GzPayTransaction();
        tx.setId(id);
        tx.setOutTradeNo(outTradeNo);
        tx.setStatus(PayStatus.PENDING);
        tx.setBusinessType("preorder");
        LocalDateTime created = LocalDateTime.now().minusMinutes(minutesAgo);
        tx.setCreateTime(Date.from(created.atZone(ZoneId.systemDefault()).toInstant()));
        return tx;
    }

    /** mock 查单返回指定 trade_state。 */
    private QueryResult qr(String outTradeNo, String tradeState) {
        boolean success = "SUCCESS".equals(tradeState);
        return new QueryResult(outTradeNo, success ? "wx_q_" + outTradeNo : null, tradeState,
            success ? 9900L : null, null, "{\"trade_state\":\"" + tradeState + "\",\"source\":\"query\"}");
    }

    /** 把 TenantHelper.ignore(Supplier) 直接执行 supplier（脱离租户上下文）。 */
    private MockedStatic<TenantHelper> mockTenant() {
        MockedStatic<TenantHelper> mocked = mockStatic(TenantHelper.class);
        mocked.when(() -> TenantHelper.ignore(any(Supplier.class)))
            .thenAnswer(inv -> ((Supplier<?>) inv.getArgument(0)).get());
        return mocked;
    }

    // ============================================================
    //  AC 4：SUCCESS → reconcilePaid 被调（走 PAY-101 同一幂等补单路径）+ 补单计数 +1
    // ============================================================

    @Test
    @DisplayName("AC4：查到 SUCCESS → 委托 reconcilePaid（补单单点）+ 补单计数 1，不另写 paid 推进")
    void scan_success_delegatesReconcilePaid() {
        try (MockedStatic<TenantHelper> ignored = mockTenant()) {
            GzPayTransaction tx = pendingTx(1001L, "PREORD-20260608-000001", 10);
            when(transactionMapper.selectReconcilePendingIds(any(), any(), eq(100)))
                .thenReturn(List.of(1001L));
            when(transactionMapper.selectById(1001L)).thenReturn(tx);
            when(wechatPayClient.queryByOutTradeNo("PREORD-20260608-000001"))
                .thenReturn(qr("PREORD-20260608-000001", "SUCCESS"));
            when(transactionService.reconcilePaid(eq(1001L), eq("wx_q_PREORD-20260608-000001"), any(), any()))
                .thenReturn(ReconcileOutcome.PAID);

            ReconcileResult result = service.scanAndReconcile();

            assertEquals(1, result.scanned());
            assertEquals(1, result.paid());
            assertEquals(0, result.skipped());
            // 补单走 PAY-101 单点（强约束 #1）
            verify(transactionService, times(1)).reconcilePaid(eq(1001L), any(), any(), any());
            // SUCCESS 分支绝不走 closed / failed / timeout 推进
            verify(transactionMapper, never()).markClosed(anyLong(), any());
            verify(transactionMapper, never()).markFailed(anyLong());
            verify(transactionMapper, never()).markTimeout(anyLong(), any());
        }
    }

    // ============================================================
    //  AC 5：同笔重复扫 → reconcilePaid 第二轮返 SKIPPED_TERMINAL → 补单仅计一次（幂等）
    // ============================================================

    @Test
    @DisplayName("AC5：同笔连扫两轮 → 第一轮 PAID / 第二轮 SKIPPED_TERMINAL（已 paid），补单计数仅第一轮为 1")
    void scan_idempotent_secondRoundSkipped() {
        try (MockedStatic<TenantHelper> ignored = mockTenant()) {
            GzPayTransaction tx = pendingTx(1001L, "PREORD-20260608-000001", 10);
            when(transactionMapper.selectReconcilePendingIds(any(), any(), eq(100)))
                .thenReturn(List.of(1001L));
            when(transactionMapper.selectById(1001L)).thenReturn(tx);
            when(wechatPayClient.queryByOutTradeNo("PREORD-20260608-000001"))
                .thenReturn(qr("PREORD-20260608-000001", "SUCCESS"));
            // 第一轮 PAID，第二轮已终态（行锁内判定已 paid）→ SKIPPED_TERMINAL
            when(transactionService.reconcilePaid(eq(1001L), any(), any(), any()))
                .thenReturn(ReconcileOutcome.PAID)
                .thenReturn(ReconcileOutcome.SKIPPED_TERMINAL);

            ReconcileResult r1 = service.scanAndReconcile();
            ReconcileResult r2 = service.scanAndReconcile();

            assertEquals(1, r1.paid(), "第一轮补单成功");
            assertEquals(0, r2.paid(), "第二轮已终态 → 不再计补单（幂等）");
            assertEquals(1, r2.skipped(), "第二轮归入 skipped（已终态）");
            // reconcilePaid 被调两次（每轮一次），但只有第一次真正推进 —— handler 至多调一次由 reconcilePaid 内部保证
            verify(transactionService, times(2)).reconcilePaid(eq(1001L), any(), any(), any());
        }
    }

    // ============================================================
    //  AC 6：NOTPAY 且 < 30min → 保持 pending、不关单、不补单
    // ============================================================

    @Test
    @DisplayName("AC6：查到 NOTPAY 且 < 30min → 保持 pending，不 closeOrder / 不 reconcilePaid / 不 markTimeout")
    void scan_notpay_under30min_keepsPending() {
        try (MockedStatic<TenantHelper> ignored = mockTenant()) {
            GzPayTransaction tx = pendingTx(1001L, "PREORD-20260608-000001", 10); // 10min 前
            when(transactionMapper.selectReconcilePendingIds(any(), any(), eq(100)))
                .thenReturn(List.of(1001L));
            when(transactionMapper.selectById(1001L)).thenReturn(tx);
            when(wechatPayClient.queryByOutTradeNo("PREORD-20260608-000001"))
                .thenReturn(qr("PREORD-20260608-000001", "NOTPAY"));

            ReconcileResult result = service.scanAndReconcile();

            assertEquals(1, result.scanned());
            assertEquals(1, result.pending(), "保持 pending");
            assertEquals(0, result.timeout());
            verify(wechatPayClient, never()).closeOrder(any());
            verify(transactionService, never()).reconcilePaid(anyLong(), any(), any(), any());
            verify(transactionMapper, never()).markTimeout(anyLong(), any());
        }
    }

    // ============================================================
    //  AC 8：NOTPAY 且 > 30min → closeOrder + markTimeout（防偷付）
    // ============================================================

    @Test
    @DisplayName("AC8：查到 NOTPAY 且 > 30min → 调微信 closeOrder + markTimeout → 计 timeout")
    void scan_notpay_over30min_closeAndTimeout() {
        try (MockedStatic<TenantHelper> ignored = mockTenant()) {
            GzPayTransaction tx = pendingTx(1001L, "PREORD-20260608-000001", 40); // 40min 前
            when(transactionMapper.selectReconcilePendingIds(any(), any(), eq(100)))
                .thenReturn(List.of(1001L));
            when(transactionMapper.selectById(1001L)).thenReturn(tx);
            when(wechatPayClient.queryByOutTradeNo("PREORD-20260608-000001"))
                .thenReturn(qr("PREORD-20260608-000001", "NOTPAY"));
            when(transactionMapper.markTimeout(eq(1001L), any())).thenReturn(1);

            ReconcileResult result = service.scanAndReconcile();

            assertEquals(1, result.timeout(), "超 30min NOTPAY → timeout 计数");
            verify(wechatPayClient, times(1)).closeOrder("PREORD-20260608-000001");
            verify(transactionMapper, times(1)).markTimeout(eq(1001L), any());
            verify(transactionService, never()).reconcilePaid(anyLong(), any(), any(), any());
        }
    }

    // ============================================================
    //  AC 7：CLOSED → markClosed / PAYERROR → markFailed / USERPAYING → 保持 pending
    // ============================================================

    @Test
    @DisplayName("AC7：CLOSED → markClosed（closed）/ PAYERROR → markFailed（failed）/ USERPAYING → 保持 pending")
    void scan_closed_payerror_userpaying_branches() {
        try (MockedStatic<TenantHelper> ignored = mockTenant()) {
            GzPayTransaction txClosed = pendingTx(1001L, "PREORD-A", 10);
            GzPayTransaction txError = pendingTx(1002L, "PREORD-B", 10);
            GzPayTransaction txPaying = pendingTx(1003L, "PREORD-C", 10);
            when(transactionMapper.selectReconcilePendingIds(any(), any(), eq(100)))
                .thenReturn(List.of(1001L, 1002L, 1003L));
            when(transactionMapper.selectById(1001L)).thenReturn(txClosed);
            when(transactionMapper.selectById(1002L)).thenReturn(txError);
            when(transactionMapper.selectById(1003L)).thenReturn(txPaying);
            when(wechatPayClient.queryByOutTradeNo("PREORD-A")).thenReturn(qr("PREORD-A", "CLOSED"));
            when(wechatPayClient.queryByOutTradeNo("PREORD-B")).thenReturn(qr("PREORD-B", "PAYERROR"));
            when(wechatPayClient.queryByOutTradeNo("PREORD-C")).thenReturn(qr("PREORD-C", "USERPAYING"));
            lenient().when(transactionMapper.markClosed(eq(1001L), any())).thenReturn(1);
            lenient().when(transactionMapper.markFailed(eq(1002L))).thenReturn(1);

            ReconcileResult result = service.scanAndReconcile();

            assertEquals(3, result.scanned());
            assertEquals(1, result.closed(), "CLOSED → closed");
            assertEquals(1, result.failed(), "PAYERROR → failed");
            assertEquals(1, result.pending(), "USERPAYING → 保持 pending");
            verify(transactionMapper, times(1)).markClosed(eq(1001L), any());
            verify(transactionMapper, times(1)).markFailed(eq(1002L));
            // USERPAYING 不推进任何终态
            verify(transactionMapper, never()).markClosed(eq(1003L), any());
            verify(transactionMapper, never()).markFailed(eq(1003L));
            verify(transactionService, never()).reconcilePaid(anyLong(), any(), any(), any());
        }
    }

    // ============================================================
    //  AC 5 补充：扫描到推进间被并发回调改 paid（selectById 已非 pending）→ 跳过、不查单
    // ============================================================

    @Test
    @DisplayName("AC5 并发：selectById 时已非 pending（并发回调改 paid）→ 跳过，不调查单 / 不补单")
    void scan_alreadyTerminal_skips() {
        try (MockedStatic<TenantHelper> ignored = mockTenant()) {
            GzPayTransaction tx = pendingTx(1001L, "PREORD-X", 10);
            tx.setStatus(PayStatus.PAID); // 扫描列表后、取详情前被并发回调改了
            when(transactionMapper.selectReconcilePendingIds(any(), any(), eq(100)))
                .thenReturn(List.of(1001L));
            when(transactionMapper.selectById(1001L)).thenReturn(tx);

            ReconcileResult result = service.scanAndReconcile();

            assertEquals(1, result.scanned());
            assertEquals(1, result.skipped());
            verify(wechatPayClient, never()).queryByOutTradeNo(any());
            verify(transactionService, never()).reconcilePaid(anyLong(), any(), any(), any());
        }
    }

    // ============================================================
    //  风险 R2：单条异常隔离（查单抛异常 → 该笔跳过，不卡整批）
    // ============================================================

    @Test
    @DisplayName("R2：单条查单抛异常 → 该笔归 skipped，不影响同轮其它单")
    void scan_singleException_isolated() {
        try (MockedStatic<TenantHelper> ignored = mockTenant()) {
            GzPayTransaction txBad = pendingTx(1001L, "PREORD-BAD", 10);
            GzPayTransaction txGood = pendingTx(1002L, "PREORD-GOOD", 10);
            when(transactionMapper.selectReconcilePendingIds(any(), any(), eq(100)))
                .thenReturn(List.of(1001L, 1002L));
            when(transactionMapper.selectById(1001L)).thenReturn(txBad);
            when(transactionMapper.selectById(1002L)).thenReturn(txGood);
            when(wechatPayClient.queryByOutTradeNo("PREORD-BAD"))
                .thenThrow(new RuntimeException("微信查单网络超时"));
            when(wechatPayClient.queryByOutTradeNo("PREORD-GOOD"))
                .thenReturn(qr("PREORD-GOOD", "SUCCESS"));
            when(transactionService.reconcilePaid(eq(1002L), any(), any(), any()))
                .thenReturn(ReconcileOutcome.PAID);

            ReconcileResult result = service.scanAndReconcile();

            assertEquals(2, result.scanned());
            assertEquals(1, result.paid(), "坏单不影响好单补单");
            assertEquals(1, result.skipped(), "坏单归 skipped");
        }
    }
}
