package org.dromara.gz.common.pay.service;

import org.dromara.common.tenant.helper.TenantHelper;
import org.dromara.gz.common.pay.config.WechatPayProperties;
import org.dromara.gz.common.pay.domain.bo.CreateOrderBo;
import org.dromara.gz.common.pay.domain.bo.GzPayTestCreateBo;
import org.dromara.gz.common.pay.domain.entity.GzPayCallbackLog;
import org.dromara.gz.common.pay.domain.entity.GzPayTransaction;
import org.dromara.gz.common.pay.domain.vo.MpPayParamsVO;
import org.dromara.gz.common.pay.enums.PayStatus;
import org.dromara.gz.common.pay.mapper.GzPayCallbackLogMapper;
import org.dromara.gz.common.pay.mapper.GzPayTransactionMapper;
import org.dromara.gz.common.pay.service.IGzPayTransactionService.ExpireResult;
import org.dromara.gz.common.pay.service.impl.GzPayTransactionServiceImpl;
import org.dromara.gz.common.pay.service.internal.IWechatPayClient;
import org.dromara.gz.common.pay.service.internal.IWechatPayClient.CallbackResult;
import org.dromara.gz.common.pay.service.internal.IWechatPayClient.JsapiPayParams;
import org.dromara.gz.common.pay.service.internal.IWechatPayClient.NotifyContext;
import org.dromara.gz.common.pay.service.internal.PayOrderNoGenerator;
import org.dromara.gz.common.pay.service.internal.WechatPayVerifyException;
import org.dromara.gz.common.pay.service.spi.PayCallbackDispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.time.LocalDateTime;
import org.dromara.gz.common.pay.shipping.ShippingInfo;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * {@link GzPayTransactionServiceImpl} 单测（GZ-PAY-001 AC 10，≥ 5 个测试方法，全 mock）。
 *
 * <p>覆盖 doc/10 §2 通道 HelloWorld 全链路（mock {@link IWechatPayClient}）：</p>
 * <ol>
 *   <li>happy path — 统一下单 → 回调 → paid（createTestOrder + handlePaymentNotify）</li>
 *   <li>重复回调 — 同订单第二次回调（已 paid）→ duplicated，不再 markPaid</li>
 *   <li>验签失败 — parser throw → 透传异常 + 不写 received 日志（controller 兜底写 failed）</li>
 *   <li>乐观锁冲突 — markPaid affected=0 → 视为重复，不报错</li>
 *   <li>超时关单 — expire 扫描 + 条件 UPDATE timeout 统计</li>
 * </ol>
 *
 * @author kevin-coder (sensenran-guzi · GZ-PAY-001)
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class GzPayTransactionServiceImplTest {

    @Mock
    private GzPayTransactionMapper transactionMapper;

    @Mock
    private GzPayCallbackLogMapper callbackLogMapper;

    @Mock
    private PayOrderNoGenerator orderNoGenerator;

    @Mock
    private IWechatPayClient wechatPayClient;

    @Mock
    private PayCallbackDispatcher callbackDispatcher;

    @Mock
    private IGzPayShippingService shippingService;

    private WechatPayProperties payProperties;

    private GzPayTransactionServiceImpl service;

    @BeforeEach
    void setUp() {
        payProperties = new WechatPayProperties();
        payProperties.setClientMode("mock");
        payProperties.getTest().setAmountCent(1L);
        // 发货信息上报与 onPaid 解耦：mock dispatcher 默认无桩 → resolveShippingInfo 返 null 会 NPE，
        // 显式桩成 empty（test 单本就不接订单中心，不触 enqueue）
        lenient().when(callbackDispatcher.resolveShippingInfo(any())).thenReturn(Optional.empty());
        @SuppressWarnings("unchecked")
        ObjectProvider<PayCallbackDispatcher> callbackDispatcherProvider = mock(ObjectProvider.class);
        lenient().when(callbackDispatcherProvider.getObject()).thenReturn(callbackDispatcher);
        service = new GzPayTransactionServiceImpl(
            transactionMapper, callbackLogMapper, orderNoGenerator, wechatPayClient, payProperties, shippingService, callbackDispatcherProvider);
    }

    // ============================================================
    //  1. happy path：统一下单 → 拿 prepay_id → 回调 → paid
    // ============================================================

    @Test
    @DisplayName("happy path：发起测试单 → created→pending + 返回 5 参签名")
    void createTestOrder_happyPath() {
        GzPayTestCreateBo bo = new GzPayTestCreateBo();
        bo.setAmountCent(1L);
        bo.setOpenid("openid_test");

        when(orderNoGenerator.generate(anyString())).thenReturn("TEST-20260604-000001");
        // insert 成功（无 DuplicateKeyException）；插入后 service 用 tx.getId() —— 模拟 DB 回填 id
        doAnswer(inv -> {
            GzPayTransaction tx = inv.getArgument(0);
            tx.setId(1001L);
            return 1;
        }).when(transactionMapper).insert(any(GzPayTransaction.class));
        when(wechatPayClient.createJsapiOrder(any())).thenReturn("mock_prepay_TEST-20260604-000001");
        when(transactionMapper.markPending(eq(1001L), anyString())).thenReturn(1);
        when(wechatPayClient.buildPayParams(anyString()))
            .thenReturn(new JsapiPayParams("1717480000", "noncestr", "prepay_id=mock_prepay_x", "RSA", "paysign_x"));

        MpPayParamsVO vo = service.createTestOrder(bo, 1L);

        assertNotNull(vo);
        assertEquals("TEST-20260604-000001", vo.getOutTradeNo());
        assertEquals("RSA", vo.getSignType());
        assertEquals("paysign_x", vo.getPaySign());
        verify(transactionMapper).markPending(eq(1001L), eq("mock_prepay_TEST-20260604-000001"));
    }

    @Test
    @DisplayName("happy path：回调验签成功 → 乐观锁 markPaid affected=1 → 写 received + processed 日志")
    void handlePaymentNotify_happyPath() {
        NotifyContext ctx = new NotifyContext("0", "n", "sig", "serial", "{}");
        when(wechatPayClient.parseAndVerifyNotify(ctx)).thenReturn(
            new CallbackResult("wx_txn_1", "TEST-20260604-000001", "SUCCESS", 1L, null, "{decrypted}"));

        GzPayTransaction tx = new GzPayTransaction();
        tx.setId(1001L);
        tx.setVersion(0);
        tx.setStatus(PayStatus.PENDING);
        when(transactionMapper.selectByOutTradeNo("TEST-20260604-000001")).thenReturn(tx);
        when(transactionMapper.markPaid(eq(1001L), eq(0), eq("wx_txn_1"), any(), any())).thenReturn(1);

        boolean ok = service.handlePaymentNotify(ctx);

        assertTrue(ok);
        verify(transactionMapper).markPaid(eq(1001L), eq(0), eq("wx_txn_1"), any(), any());
        // received + processed 两条审计日志
        verify(callbackLogMapper, times(2)).insert(any(GzPayCallbackLog.class));
        // markPaid 成功 → SPI 分发被调一次（test 单 dispatcher 内部跳过，但 service 仍调 dispatch）
        verify(callbackDispatcher, times(1)).dispatch(any(GzPayTransaction.class));
        // 发货上报总开关默认关 → 不入队（拼豆服务类经营类目未开放上传，避免无效失败重试）
        verify(shippingService, never()).enqueue(any(GzPayTransaction.class), any());
    }

    @Test
    @DisplayName("发货上报开关开：回调 paid + resolveShippingInfo 命中 → enqueue 入队（预购实物电商上线后场景）")
    void handlePaymentNotify_shippingEnabled_enqueues() {
        payProperties.setShippingUploadEnabled(true);
        NotifyContext ctx = new NotifyContext("0", "n", "sig", "serial", "{}");
        when(wechatPayClient.parseAndVerifyNotify(ctx)).thenReturn(
            new CallbackResult("wx_txn_2", "PINDOU-20260704-000001", "SUCCESS", 1L, null, "{decrypted}"));

        GzPayTransaction tx = new GzPayTransaction();
        tx.setId(2002L);
        tx.setVersion(0);
        tx.setStatus(PayStatus.PENDING);
        when(transactionMapper.selectByOutTradeNo("PINDOU-20260704-000001")).thenReturn(tx);
        when(transactionMapper.markPaid(eq(2002L), eq(0), eq("wx_txn_2"), any(), any())).thenReturn(1);
        when(callbackDispatcher.resolveShippingInfo(any()))
            .thenReturn(Optional.of(ShippingInfo.virtual("谷子宇宙·拼豆预约")));

        boolean ok = service.handlePaymentNotify(ctx);

        assertTrue(ok);
        verify(shippingService, times(1)).enqueue(any(GzPayTransaction.class), any());
    }

    @Test
    @DisplayName("加固：回调审计日志写入失败（列截断等）→ 不阻断支付确认（仍 markPaid + dispatch + 返回 true）")
    void handlePaymentNotify_callbackLogInsertFails_stillConfirmsPaid() {
        NotifyContext ctx = new NotifyContext("0", "n", "sig", "serial", "{}");
        when(wechatPayClient.parseAndVerifyNotify(ctx)).thenReturn(
            new CallbackResult("wx_txn_1", "TEST-20260604-000001", "SUCCESS", 1L, null, "{decrypted}"));

        GzPayTransaction tx = new GzPayTransaction();
        tx.setId(1001L);
        tx.setVersion(0);
        tx.setStatus(PayStatus.PENDING);
        when(transactionMapper.selectByOutTradeNo("TEST-20260604-000001")).thenReturn(tx);
        when(transactionMapper.markPaid(eq(1001L), eq(0), eq("wx_txn_1"), any(), any())).thenReturn(1);
        // 模拟真机首单事故：signature 列截断 → 审计 INSERT 抛 DataIntegrityViolationException
        when(callbackLogMapper.insert(any(GzPayCallbackLog.class)))
            .thenThrow(new org.springframework.dao.DataIntegrityViolationException("Data too long for column 'signature'"));

        boolean ok = service.handlePaymentNotify(ctx);

        // 关键保证：审计写失败被 try/catch 吞掉 → 支付照常确认（markPaid + SPI 分发），不回滚
        assertTrue(ok, "审计写失败不应阻断支付确认");
        verify(transactionMapper).markPaid(eq(1001L), eq(0), eq("wx_txn_1"), any(), any());
        verify(callbackDispatcher, times(1)).dispatch(any(GzPayTransaction.class));
    }

    // ============================================================
    //  2. 重复回调：订单已 paid → duplicated，不再 markPaid
    // ============================================================

    @Test
    @DisplayName("重复回调：订单已 paid → 标 duplicated，不调 markPaid")
    void handlePaymentNotify_duplicated_alreadyPaid() {
        NotifyContext ctx = new NotifyContext("0", "n", "sig", "serial", "{}");
        when(wechatPayClient.parseAndVerifyNotify(ctx)).thenReturn(
            new CallbackResult("wx_txn_1", "TEST-20260604-000001", "SUCCESS", 1L, null, "{decrypted}"));

        GzPayTransaction tx = new GzPayTransaction();
        tx.setId(1001L);
        tx.setVersion(1);
        tx.setStatus(PayStatus.PAID);
        when(transactionMapper.selectByOutTradeNo("TEST-20260604-000001")).thenReturn(tx);

        boolean ok = service.handlePaymentNotify(ctx);

        assertTrue(ok, "重复回调应幂等返回成功");
        verify(transactionMapper, never()).markPaid(anyLong(), any(), anyString(), any(), any());
        // received + duplicated 两条
        verify(callbackLogMapper, times(2)).insert(any(GzPayCallbackLog.class));
        // 已 paid 重复回调 → 不重复分发 SPI handler（AC 6 第一道防线：onPaid 至多一次）
        verify(callbackDispatcher, never()).dispatch(any(GzPayTransaction.class));
    }

    // ============================================================
    //  3. 验签失败：parser throw → 透传 WechatPayVerifyException（controller 兜底写 failed + 401）
    // ============================================================

    @Test
    @DisplayName("验签失败：parser throw → 透传异常，不更新订单 + 不写 received 日志")
    void handlePaymentNotify_verifyFailed() {
        NotifyContext ctx = new NotifyContext("0", "n", "bad_sig", "serial", "ciphertext");
        when(wechatPayClient.parseAndVerifyNotify(ctx))
            .thenThrow(new WechatPayVerifyException("微信回调验签失败: signature verification failed"));

        WechatPayVerifyException ex = assertThrows(WechatPayVerifyException.class,
            () -> service.handlePaymentNotify(ctx));
        assertTrue(ex.getMessage().contains("验签失败"));
        // 验签失败发生在写 received 之前 → service 内不写日志（由 controller 写 failed）
        verify(callbackLogMapper, never()).insert(any(GzPayCallbackLog.class));
        verify(transactionMapper, never()).markPaid(anyLong(), any(), anyString(), any(), any());
    }

    // ============================================================
    //  4. 乐观锁冲突：markPaid affected=0（并发已处理）→ 视为重复，不报错
    // ============================================================

    @Test
    @DisplayName("乐观锁冲突：markPaid affected=0 → 标 duplicated，幂等返回成功")
    void handlePaymentNotify_optimisticLockConflict() {
        NotifyContext ctx = new NotifyContext("0", "n", "sig", "serial", "{}");
        when(wechatPayClient.parseAndVerifyNotify(ctx)).thenReturn(
            new CallbackResult("wx_txn_1", "TEST-20260604-000001", "SUCCESS", 1L, null, "{decrypted}"));

        GzPayTransaction tx = new GzPayTransaction();
        tx.setId(1001L);
        tx.setVersion(0);
        tx.setStatus(PayStatus.PENDING);
        when(transactionMapper.selectByOutTradeNo("TEST-20260604-000001")).thenReturn(tx);
        // 并发：另一回调已抢先把 pending→paid 且 version+1 → 本次 affected=0
        when(transactionMapper.markPaid(eq(1001L), eq(0), eq("wx_txn_1"), any(), any())).thenReturn(0);

        boolean ok = service.handlePaymentNotify(ctx);

        assertTrue(ok, "乐观锁冲突应幂等返回成功");
        verify(transactionMapper).markPaid(eq(1001L), eq(0), eq("wx_txn_1"), any(), any());
        // received + duplicated 两条
        verify(callbackLogMapper, times(2)).insert(any(GzPayCallbackLog.class));
        // 乐观锁 affected=0（并发已处理）→ 不重复分发 SPI handler（AC 6 第二道防线）
        verify(callbackDispatcher, never()).dispatch(any(GzPayTransaction.class));
    }

    // ============================================================
    //  5. 超时关单：扫 pending 过期 → 条件 UPDATE timeout 统计
    // ============================================================

    @Test
    @DisplayName("超时关单：扫 2 单 → 1 单 affected=1 关单 / 1 单 affected=0 跳过（已 paid）")
    void expireTimeoutOrders_scanAndClose() {
        try (MockedStatic<TenantHelper> mocked = mockStatic(TenantHelper.class)) {
            // TenantHelper.ignore(Supplier) 直接执行 supplier（脱离租户上下文）
            mocked.when(() -> TenantHelper.ignore(any(Supplier.class)))
                .thenAnswer(inv -> ((Supplier<?>) inv.getArgument(0)).get());

            when(transactionMapper.selectExpiredPendingIds(any(LocalDateTime.class)))
                .thenReturn(List.of(2001L, 2002L));
            when(transactionMapper.markTimeout(eq(2001L), any())).thenReturn(1);  // 成功关单
            when(transactionMapper.markTimeout(eq(2002L), any())).thenReturn(0);  // 已被回调改 paid，跳过

            ExpireResult result = service.expireTimeoutOrders();

            assertEquals(2, result.scanned());
            assertEquals(1, result.closed());
            assertEquals(1, result.skipped());
            verify(transactionMapper).markTimeout(eq(2001L), any());
            verify(transactionMapper).markTimeout(eq(2002L), any());
        }
    }

    // ============================================================
    //  PAY-101 AC 1：通用业务建单（createBusinessOrder）
    // ============================================================

    @Test
    @DisplayName("PAY-101 AC 1：createBusinessOrder(preorder) → 落 created + business_type/business_order_no 写入 + created→pending")
    void createBusinessOrder_preorder_happyPath() {
        CreateOrderBo bo = CreateOrderBo.builder()
            .businessType(org.dromara.gz.common.pay.enums.PayBusinessType.PREORDER)
            .businessOrderNo("PREORD-ORDER-001")
            .amountCent(9900L)
            .openid("openid_buyer")
            .userId(2001L)
            .description("谷子宇宙预购单")
            .build();

        when(orderNoGenerator.generate(org.dromara.gz.common.pay.enums.PayBusinessType.PREORDER))
            .thenReturn("PREORD-20260604-000001");
        org.mockito.ArgumentCaptor<GzPayTransaction> insertCap = org.mockito.ArgumentCaptor.forClass(GzPayTransaction.class);
        doAnswer(inv -> {
            GzPayTransaction tx = inv.getArgument(0);
            tx.setId(3001L);
            return 1;
        }).when(transactionMapper).insert(insertCap.capture());
        when(wechatPayClient.createJsapiOrder(any())).thenReturn("mock_prepay_PREORD-20260604-000001");
        when(transactionMapper.markPending(eq(3001L), anyString())).thenReturn(1);
        when(wechatPayClient.buildPayParams(anyString()))
            .thenReturn(new JsapiPayParams("1717480000", "noncestr", "prepay_id=mock_prepay_x", "RSA", "paysign_x"));

        MpPayParamsVO vo = service.createBusinessOrder(bo);

        assertNotNull(vo);
        assertEquals("PREORD-20260604-000001", vo.getOutTradeNo());
        // AC 3：business_type 分流真源 + business_order_no 落 transaction 表（fee_cent 留 NULL，强约束 #7）
        GzPayTransaction inserted = insertCap.getValue();
        assertEquals("preorder", inserted.getBusinessType());
        assertEquals("PREORD-ORDER-001", inserted.getBusinessOrderNo());
        assertEquals(9900L, inserted.getAmountCent());
        assertEquals(2001L, inserted.getUserId());
        assertEquals(PayStatus.CREATED, inserted.getStatus());
        assertNull(inserted.getFeeCent(), "fee_cent 建单时留 NULL（PAY-104 回写）");
        assertNotNull(inserted.getExpireTime());
        verify(transactionMapper).markPending(eq(3001L), eq("mock_prepay_PREORD-20260604-000001"));
    }

    @Test
    @DisplayName("PAY-101 AC 2：未知 business_type → IllegalArgumentException（前缀映射缺失早失败）")
    void createBusinessOrder_unknownType_fails() {
        CreateOrderBo bo = CreateOrderBo.builder()
            .businessType("unknown_biz")
            .businessOrderNo("X-001")
            .amountCent(100L)
            .openid("openid_x")
            .userId(1L)
            .description("x")
            .build();

        assertThrows(IllegalArgumentException.class, () -> service.createBusinessOrder(bo));
        verify(transactionMapper, never()).insert(any(GzPayTransaction.class));
    }

    // ============================================================
    //  PAY-101 AC 5/9：SPI 路由分发（preorder 回调 → dispatch 命中，传 paid 交易行）
    // ============================================================

    @Test
    @DisplayName("PAY-101 AC 5：preorder 回调 markPaid 成功 → dispatch 被调一次 + 传入 paid 交易行（business_type/business_order_no/transaction_id 齐全）")
    void handlePaymentNotify_preorder_spiDispatched() {
        NotifyContext ctx = new NotifyContext("0", "n", "sig", "serial", "{}");
        when(wechatPayClient.parseAndVerifyNotify(ctx)).thenReturn(
            new CallbackResult("wx_txn_pre", "PREORD-20260604-000001", "SUCCESS", 9900L, null, "{decrypted}"));

        GzPayTransaction tx = new GzPayTransaction();
        tx.setId(3001L);
        tx.setVersion(1);
        tx.setStatus(PayStatus.PENDING);
        tx.setBusinessType(org.dromara.gz.common.pay.enums.PayBusinessType.PREORDER);
        tx.setBusinessOrderNo("PREORD-ORDER-001");
        tx.setOutTradeNo("PREORD-20260604-000001");
        when(transactionMapper.selectByOutTradeNo("PREORD-20260604-000001")).thenReturn(tx);
        when(transactionMapper.markPaid(eq(3001L), eq(1), eq("wx_txn_pre"), any(), any())).thenReturn(1);

        boolean ok = service.handlePaymentNotify(ctx);

        assertTrue(ok);
        org.mockito.ArgumentCaptor<GzPayTransaction> cap = org.mockito.ArgumentCaptor.forClass(GzPayTransaction.class);
        verify(callbackDispatcher, times(1)).dispatch(cap.capture());
        GzPayTransaction dispatched = cap.getValue();
        assertEquals("preorder", dispatched.getBusinessType());
        assertEquals("PREORD-ORDER-001", dispatched.getBusinessOrderNo());
        assertEquals("wx_txn_pre", dispatched.getTransactionId(), "dispatch 前已把 transaction_id 同步进内存对象");
        assertEquals(PayStatus.PAID, dispatched.getStatus());
    }

    // ============================================================
    //  PAY-101 AC 5：handler 抛异常 → 透传 → 整笔事务回滚（callback_log processed 不写）
    // ============================================================

    @Test
    @DisplayName("PAY-101 AC 5：SPI handler 抛异常 → 异常透传（整笔事务回滚让微信重试）+ processed 日志未写")
    void handlePaymentNotify_handlerThrows_propagatesForRollback() {
        NotifyContext ctx = new NotifyContext("0", "n", "sig", "serial", "{}");
        when(wechatPayClient.parseAndVerifyNotify(ctx)).thenReturn(
            new CallbackResult("wx_txn_pre", "PREORD-20260604-000001", "SUCCESS", 9900L, null, "{decrypted}"));

        GzPayTransaction tx = new GzPayTransaction();
        tx.setId(3001L);
        tx.setVersion(1);
        tx.setStatus(PayStatus.PENDING);
        tx.setBusinessType(org.dromara.gz.common.pay.enums.PayBusinessType.PREORDER);
        tx.setBusinessOrderNo("PREORD-ORDER-001");
        when(transactionMapper.selectByOutTradeNo("PREORD-20260604-000001")).thenReturn(tx);
        when(transactionMapper.markPaid(eq(3001L), eq(1), eq("wx_txn_pre"), any(), any())).thenReturn(1);
        // handler 业务异常（如 gz_ord_order 出单失败）
        doThrow(new RuntimeException("预购出单失败：库存归还冲突"))
            .when(callbackDispatcher).dispatch(any(GzPayTransaction.class));

        RuntimeException ex = assertThrows(RuntimeException.class, () -> service.handlePaymentNotify(ctx));
        assertTrue(ex.getMessage().contains("出单失败"));
        // markPaid 已调（但事务会回滚）；processed 日志在 dispatch 之后 → 未写（仅 received 1 条）
        verify(transactionMapper).markPaid(eq(3001L), eq(1), eq("wx_txn_pre"), any(), any());
        verify(callbackLogMapper, times(1)).insert(any(GzPayCallbackLog.class));
    }

    // ============================================================
    //  PAY-102 AC 4/5：主动查单补单单点（reconcilePaid）—— 回调与查单共用同一 markPaid+SPI+log 核心
    // ============================================================

    @Test
    @DisplayName("PAY-102 AC4：reconcilePaid 锁行 pending → markPaid + dispatch 一次 + processed 日志（与回调同一补单单点）")
    void reconcilePaid_pending_marksPaidAndDispatchOnce() {
        GzPayTransaction tx = new GzPayTransaction();
        tx.setId(5001L);
        tx.setVersion(2);
        tx.setStatus(PayStatus.PENDING);
        tx.setBusinessType(org.dromara.gz.common.pay.enums.PayBusinessType.PREORDER);
        tx.setBusinessOrderNo("PREORD-ORDER-Q1");
        tx.setOutTradeNo("PREORD-20260608-000009");
        when(transactionMapper.selectByIdForUpdate(5001L)).thenReturn(tx);
        when(transactionMapper.markPaid(eq(5001L), eq(2), eq("wx_q_9"), any(), any())).thenReturn(1);

        IGzPayTransactionService.ReconcileOutcome outcome =
            service.reconcilePaid(5001L, "wx_q_9", null, "{\"trade_state\":\"SUCCESS\",\"source\":\"query\"}");

        assertEquals(IGzPayTransactionService.ReconcileOutcome.PAID, outcome);
        // 行锁加载（与回调走 selectByOutTradeNo 不同入口，但补单核心相同）
        verify(transactionMapper).selectByIdForUpdate(5001L);
        verify(transactionMapper).markPaid(eq(5001L), eq(2), eq("wx_q_9"), any(), any());
        // SPI 分发一次（test 单 dispatcher 内跳过，但 service 仍调 dispatch）
        verify(callbackDispatcher, times(1)).dispatch(any(GzPayTransaction.class));
        // callback_log processed 一条（raw_body = 查单报文，决策 D5 来源区分）
        verify(callbackLogMapper, times(1)).insert(any(GzPayCallbackLog.class));
    }

    @Test
    @DisplayName("PAY-102 AC5：reconcilePaid 锁行已 paid（并发回调先推进）→ SKIPPED_TERMINAL，不二次 markPaid / 不二次 dispatch")
    void reconcilePaid_alreadyPaid_idempotentSkip() {
        GzPayTransaction tx = new GzPayTransaction();
        tx.setId(5001L);
        tx.setVersion(3);
        tx.setStatus(PayStatus.PAID); // 并发被动回调已推进
        tx.setOutTradeNo("PREORD-20260608-000009");
        when(transactionMapper.selectByIdForUpdate(5001L)).thenReturn(tx);

        IGzPayTransactionService.ReconcileOutcome outcome =
            service.reconcilePaid(5001L, "wx_q_9", null, "{\"trade_state\":\"SUCCESS\"}");

        assertEquals(IGzPayTransactionService.ReconcileOutcome.SKIPPED_TERMINAL, outcome);
        verify(transactionMapper, never()).markPaid(anyLong(), any(), anyString(), any(), any());
        verify(callbackDispatcher, never()).dispatch(any(GzPayTransaction.class));
        // 已终态不写 callback_log（不重复审计）
        verify(callbackLogMapper, never()).insert(any(GzPayCallbackLog.class));
    }

    @Test
    @DisplayName("PAY-102 AC5：reconcilePaid 锁行后乐观锁冲突（markPaid affected=0）→ 标 duplicated，不 dispatch")
    void reconcilePaid_optimisticConflict_duplicated() {
        GzPayTransaction tx = new GzPayTransaction();
        tx.setId(5001L);
        tx.setVersion(2);
        tx.setStatus(PayStatus.PENDING);
        tx.setBusinessType(org.dromara.gz.common.pay.enums.PayBusinessType.PREORDER);
        tx.setOutTradeNo("PREORD-20260608-000009");
        when(transactionMapper.selectByIdForUpdate(5001L)).thenReturn(tx);
        // 行锁内仍 pending，但 markPaid 时 version 漂移（理论上行锁后不应发生，防御性兜底）→ affected=0
        when(transactionMapper.markPaid(eq(5001L), eq(2), eq("wx_q_9"), any(), any())).thenReturn(0);

        IGzPayTransactionService.ReconcileOutcome outcome =
            service.reconcilePaid(5001L, "wx_q_9", null, "{\"trade_state\":\"SUCCESS\"}");

        assertEquals(IGzPayTransactionService.ReconcileOutcome.SKIPPED_TERMINAL, outcome,
            "行锁内 markPaid affected=0（并发已处理）→ 归 SKIPPED_TERMINAL，不计补单（幂等）");
        verify(callbackDispatcher, never()).dispatch(any(GzPayTransaction.class));
        // duplicated 一条 callback_log（审计并发冲突）
        verify(callbackLogMapper, times(1)).insert(any(GzPayCallbackLog.class));
    }
}
