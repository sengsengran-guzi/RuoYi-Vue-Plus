package org.dromara.gz.common.pay.service;

import org.dromara.common.tenant.helper.TenantHelper;
import org.dromara.gz.common.pay.config.WechatPayProperties;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
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

    private WechatPayProperties payProperties;

    private GzPayTransactionServiceImpl service;

    @BeforeEach
    void setUp() {
        payProperties = new WechatPayProperties();
        payProperties.setClientMode("mock");
        payProperties.getTest().setAmountCent(1L);
        service = new GzPayTransactionServiceImpl(
            transactionMapper, callbackLogMapper, orderNoGenerator, wechatPayClient, payProperties);
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
}
