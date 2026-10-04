package org.dromara.gz.common.pay.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.common.tenant.helper.TenantHelper;
import org.dromara.gz.common.pay.config.WechatPayProperties;
import org.dromara.gz.common.pay.domain.bo.GzPayTransactionQueryBo;
import org.dromara.gz.common.pay.domain.entity.GzPayCallbackLog;
import org.dromara.gz.common.pay.domain.entity.GzPayTransaction;
import org.dromara.gz.common.pay.domain.vo.GzPayTransactionVO;
import org.dromara.gz.common.pay.enums.PayStatus;
import org.dromara.gz.common.pay.mapper.GzPayCallbackLogMapper;
import org.dromara.gz.common.pay.mapper.GzPayChannelMapper;
import org.dromara.gz.common.pay.mapper.GzPayTransactionMapper;
import org.dromara.gz.common.pay.service.IGzPayTransactionService.ExpireResult;
import org.dromara.gz.common.pay.service.IGzPayTransactionService.ReconcileOutcome;
import org.dromara.gz.common.pay.service.impl.GzPayTransactionServiceImpl;
import org.dromara.gz.common.pay.service.impl.PayOrderQueryServiceImpl;
import org.dromara.gz.common.pay.service.internal.IWechatPayClient;
import org.dromara.gz.common.pay.service.internal.IWechatPayClient.CallbackResult;
import org.dromara.gz.common.pay.service.internal.IWechatPayClient.NotifyContext;
import org.dromara.gz.common.pay.service.internal.IWechatPayClient.QueryResult;
import org.dromara.gz.common.pay.service.internal.PayAppidResolver;
import org.dromara.gz.common.pay.service.internal.PayOrderNoGenerator;
import org.dromara.gz.common.pay.service.spi.PayCallbackDispatcher;
import org.dromara.gz.common.wechat.WxAppResolver;
import org.dromara.gz.common.wechat.WxMiniappProperties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * GZ-PAY-106 迟到付款不再被吞（FLOW:F-JP-06.step4b）+ 超时同时关微信单。
 *
 * <p>修复前：本地 {@code markTimeout} 只改本地状态；超时后到达的 SUCCESS 回调 {@code markPaid … WHERE status='pending'}
 * affected=0 → 记 {@code duplicated} 静默返回 —— 扣款成功、业务单不变、无告警。本测试锁死修复后的行为：</p>
 *
 * <ol>
 *   <li>超时任务标 timeout 成功后调 {@code closeOrder}；关单失败只记 warn，本地 timeout 不回滚</li>
 *   <li>timeout / closed 单收到 SUCCESS 回调 → callback_log {@code late_paid}（带金额 + transaction_id），
 *       <b>不</b> markPaid、<b>不</b>分发业务、<b>不</b>记 duplicated</li>
 *   <li>回调读到 pending、markPaid 前被超时任务抢先标 timeout（并发）→ 同样记 late_paid 而非 duplicated</li>
 *   <li>查单 {@code reconcilePaid} 遇 timeout → {@code LATE_PAID}；查单任务扫近 24h timeout 单，回调丢失也能发现</li>
 *   <li>admin 支付流水「迟到付款」筛选与行标记</li>
 * </ol>
 *
 * <p>真实 {@link GzPayTransactionServiceImpl} / {@link PayOrderQueryServiceImpl}，只 mock mapper 与微信通道。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-PAY-106)
 */
@Tag("dev")
class PayLatePaidTest {

    private static final String OUT_TRADE_NO = "PINDOU-20261005-000007";
    private static final String TXN_ID = "4200002612202610050000000007";

    private GzPayTransactionMapper transactionMapper;
    private GzPayCallbackLogMapper callbackLogMapper;
    private IWechatPayClient wechatPayClient;
    private PayCallbackDispatcher dispatcher;
    private GzPayTransactionServiceImpl service;
    private PayOrderQueryServiceImpl queryService;

    @BeforeAll
    static void initTableInfo() {
        // 只有 admin 列表用例会把 lambda wrapper 渲染成 SQL —— 先注册实体元数据（同 learnings：MP lambda cache）
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, GzPayTransaction.class);
        TableInfoHelper.initTableInfo(assistant, GzPayCallbackLog.class);
    }

    @BeforeEach
    void setUp() {
        transactionMapper = mock(GzPayTransactionMapper.class);
        callbackLogMapper = mock(GzPayCallbackLogMapper.class);
        wechatPayClient = mock(IWechatPayClient.class);
        dispatcher = mock(PayCallbackDispatcher.class);
        when(dispatcher.resolveShippingInfo(any())).thenReturn(Optional.empty());
        @SuppressWarnings("unchecked")
        ObjectProvider<PayCallbackDispatcher> dispatcherProvider = mock(ObjectProvider.class);
        when(dispatcherProvider.getObject()).thenReturn(dispatcher);

        WechatPayProperties props = new WechatPayProperties();
        props.setClientMode("mock");
        PayAppidResolver resolver = new PayAppidResolver(
            new WxAppResolver(new WxMiniappProperties()), mock(GzPayChannelMapper.class), props);
        service = new GzPayTransactionServiceImpl(transactionMapper, callbackLogMapper, mock(PayOrderNoGenerator.class),
            wechatPayClient, props, mock(IGzPayShippingService.class), dispatcherProvider, resolver);
        queryService = new PayOrderQueryServiceImpl(transactionMapper, wechatPayClient, service);
    }

    private static GzPayTransaction tx(long id, String status) {
        GzPayTransaction tx = new GzPayTransaction();
        tx.setId(id);
        tx.setOutTradeNo(OUT_TRADE_NO);
        tx.setBusinessType("pindou");
        tx.setBusinessOrderNo("BK20261005-000007");
        tx.setAmountCent(3000L);
        tx.setStatus(status);
        tx.setVersion(3);
        return tx;
    }

    private static NotifyContext successNotify() {
        return new NotifyContext("1759640000", "nonce", "sig_late", "serial", "{cipher}");
    }

    private static CallbackResult successCallback() {
        return new CallbackResult(TXN_ID, OUT_TRADE_NO, "SUCCESS", 3000L, null,
            "{\"out_trade_no\":\"" + OUT_TRADE_NO + "\",\"trade_state\":\"SUCCESS\"}");
    }

    private List<GzPayCallbackLog> capturedLogs(int expected) {
        ArgumentCaptor<GzPayCallbackLog> cap = ArgumentCaptor.forClass(GzPayCallbackLog.class);
        verify(callbackLogMapper, times(expected)).insert(cap.capture());
        return cap.getAllValues();
    }

    private void assertNothingAdvanced() {
        verify(transactionMapper, never()).markPaid(anyLong(), any(), anyString(), any(), any());
        verify(transactionMapper, never()).updateById(any(GzPayTransaction.class));
        verify(dispatcher, never()).dispatch(any(GzPayTransaction.class));
    }

    private static void assertLatePaidLog(GzPayCallbackLog log) {
        assertEquals(GzPayTransactionServiceImpl.CB_LATE_PAID, log.getProcessStatus());
        assertEquals("late_paid", log.getProcessStatus());
        assertEquals(OUT_TRADE_NO, log.getOutTradeNo());
        assertEquals(TXN_ID, log.getTransactionId());
        assertNotNull(log.getProcessError());
        assertTrue(log.getProcessError().contains("3000"), "说明里带金额：" + log.getProcessError());
        assertTrue(log.getProcessError().contains(TXN_ID), "说明里带 transaction_id：" + log.getProcessError());
        assertTrue(log.getProcessError().length() <= 500, "process_error 列 varchar(500)");
    }

    private static MockedStatic<TenantHelper> tenantIgnorePassThrough() {
        MockedStatic<TenantHelper> mocked = mockStatic(TenantHelper.class);
        mocked.when(() -> TenantHelper.ignore(any(Supplier.class)))
            .thenAnswer(inv -> ((Supplier<?>) inv.getArgument(0)).get());
        return mocked;
    }

    // ============================================================
    //  1. 超时任务同时关微信单
    // ============================================================

    @Test
    @DisplayName("超时任务：标 timeout 成功 → 调 closeOrder 关微信侧支付单；未标成功（已 paid）→ 不关")
    void expire_marksTimeoutThenClosesWechatOrder() {
        try (MockedStatic<TenantHelper> ignored = tenantIgnorePassThrough()) {
            when(transactionMapper.selectExpiredPendingIds(any(LocalDateTime.class))).thenReturn(List.of(81L, 82L));
            when(transactionMapper.markTimeout(eq(81L), any())).thenReturn(1);
            when(transactionMapper.markTimeout(eq(82L), any())).thenReturn(0);
            when(transactionMapper.selectById(81L)).thenReturn(tx(81L, PayStatus.TIMEOUT));

            ExpireResult r = service.expireTimeoutOrders();

            assertEquals(1, r.closed());
            assertEquals(1, r.skipped());
            verify(wechatPayClient, times(1)).closeOrder(OUT_TRADE_NO);
            verify(transactionMapper, never()).selectById(82L);
        }
    }

    @Test
    @DisplayName("超时任务：closeOrder 抛错 → 只记 warn，本地 timeout 照常计数、不回滚、整批不中断")
    void expire_closeOrderFails_localTimeoutKept() {
        try (MockedStatic<TenantHelper> ignored = tenantIgnorePassThrough()) {
            when(transactionMapper.selectExpiredPendingIds(any(LocalDateTime.class))).thenReturn(List.of(81L, 83L));
            when(transactionMapper.markTimeout(anyLong(), any())).thenReturn(1);
            when(transactionMapper.selectById(81L)).thenReturn(tx(81L, PayStatus.TIMEOUT));
            GzPayTransaction other = tx(83L, PayStatus.TIMEOUT);
            other.setOutTradeNo("PINDOU-20261005-000008");
            when(transactionMapper.selectById(83L)).thenReturn(other);
            doThrow(new RuntimeException("微信关单超时")).when(wechatPayClient).closeOrder(OUT_TRADE_NO);

            ExpireResult r = service.expireTimeoutOrders();

            assertEquals(2, r.scanned());
            assertEquals(2, r.closed(), "关微信单失败不影响本地 timeout 计数");
            assertEquals(0, r.skipped());
            verify(wechatPayClient).closeOrder("PINDOU-20261005-000008");
            verify(transactionMapper, never()).updateById(any(GzPayTransaction.class));
            verify(transactionMapper, never()).markPending(anyLong(), anyString());
        }
    }

    // ============================================================
    //  2. 回调：timeout / closed 单收到 SUCCESS
    // ============================================================

    @Test
    @DisplayName("回调：timeout 单收到 SUCCESS → late_paid（带金额 + transaction_id），不 markPaid、不分发、不记 duplicated")
    void notify_timeoutOrder_recordsLatePaid() {
        when(wechatPayClient.parseAndVerifyNotify(any())).thenReturn(successCallback());
        when(transactionMapper.selectByOutTradeNo(OUT_TRADE_NO)).thenReturn(tx(90L, PayStatus.TIMEOUT));

        boolean ok = service.handlePaymentNotify(successNotify());

        assertTrue(ok, "已如实记录，应答微信成功（避免重推刷屏）");
        assertNothingAdvanced();
        List<GzPayCallbackLog> logs = capturedLogs(2);
        assertEquals("received", logs.get(0).getProcessStatus());
        assertLatePaidLog(logs.get(1));
        assertTrue(logs.stream().noneMatch(l -> "duplicated".equals(l.getProcessStatus())), "不得再记 duplicated 吞掉");
        assertEquals("sig_late", logs.get(1).getSignature());
    }

    @Test
    @DisplayName("回调：closed 单收到 SUCCESS → 同样 late_paid，不推进 paid")
    void notify_closedOrder_recordsLatePaid() {
        when(wechatPayClient.parseAndVerifyNotify(any())).thenReturn(successCallback());
        when(transactionMapper.selectByOutTradeNo(OUT_TRADE_NO)).thenReturn(tx(91L, PayStatus.CLOSED));

        assertTrue(service.handlePaymentNotify(successNotify()));

        assertNothingAdvanced();
        assertLatePaidLog(capturedLogs(2).get(1));
    }

    @Test
    @DisplayName("回调并发：读到 pending、markPaid 前被超时任务抢先标 timeout（affected=0）→ late_paid，不是 duplicated")
    void notify_raceWithExpire_recordsLatePaidNotDuplicated() {
        when(wechatPayClient.parseAndVerifyNotify(any())).thenReturn(successCallback());
        when(transactionMapper.selectByOutTradeNo(OUT_TRADE_NO)).thenReturn(tx(92L, PayStatus.PENDING));
        when(transactionMapper.markPaid(eq(92L), eq(3), eq(TXN_ID), any(), any())).thenReturn(0);
        when(transactionMapper.selectById(92L)).thenReturn(tx(92L, PayStatus.TIMEOUT));

        assertTrue(service.handlePaymentNotify(successNotify()));

        verify(dispatcher, never()).dispatch(any(GzPayTransaction.class));
        List<GzPayCallbackLog> logs = capturedLogs(2);
        assertLatePaidLog(logs.get(1));
        assertTrue(logs.stream().noneMatch(l -> "duplicated".equals(l.getProcessStatus())));
    }

    @Test
    @DisplayName("回归：pending 单正常 SUCCESS 回调仍推进 paid + 分发 + processed（拼豆 / 回收链路不变）")
    void notify_pendingOrder_stillPaidAsBefore() {
        when(wechatPayClient.parseAndVerifyNotify(any())).thenReturn(successCallback());
        when(transactionMapper.selectByOutTradeNo(OUT_TRADE_NO)).thenReturn(tx(93L, PayStatus.PENDING));
        when(transactionMapper.markPaid(eq(93L), eq(3), eq(TXN_ID), any(), any())).thenReturn(1);

        assertTrue(service.handlePaymentNotify(successNotify()));

        verify(dispatcher, times(1)).dispatch(any(GzPayTransaction.class));
        List<GzPayCallbackLog> logs = capturedLogs(2);
        assertEquals("processed", logs.get(1).getProcessStatus());
        verify(transactionMapper, never()).selectById(anyLong());
    }

    @Test
    @DisplayName("回归：已 paid 单的重复回调仍记 duplicated（不误报迟到付款）")
    void notify_paidOrder_stillDuplicated() {
        when(wechatPayClient.parseAndVerifyNotify(any())).thenReturn(successCallback());
        when(transactionMapper.selectByOutTradeNo(OUT_TRADE_NO)).thenReturn(tx(94L, PayStatus.PAID));

        assertTrue(service.handlePaymentNotify(successNotify()));

        assertEquals("duplicated", capturedLogs(2).get(1).getProcessStatus());
        assertNothingAdvanced();
    }

    // ============================================================
    //  3. 查单：reconcilePaid + 扫近 24h timeout 单
    // ============================================================

    @Test
    @DisplayName("查单 reconcilePaid：锁行后是 timeout → LATE_PAID + late_paid 日志，不 markPaid、不分发")
    void reconcilePaid_timeout_returnsLatePaid() {
        when(transactionMapper.selectByIdForUpdate(95L)).thenReturn(tx(95L, PayStatus.TIMEOUT));

        ReconcileOutcome outcome = service.reconcilePaid(95L, TXN_ID, null, "{\"trade_state\":\"SUCCESS\",\"source\":\"query\"}");

        assertEquals(ReconcileOutcome.LATE_PAID, outcome);
        assertNothingAdvanced();
        assertLatePaidLog(capturedLogs(1).get(0));
    }

    @Test
    @DisplayName("查单任务：近 24h timeout 单查到 SUCCESS（回调丢失）→ 发现并记 late_paid；窗口下界 = now-24h")
    void scan_timeoutOrderWithLostCallback_detectedAsLatePaid() {
        try (MockedStatic<TenantHelper> ignored = tenantIgnorePassThrough()) {
            when(transactionMapper.selectReconcilePendingIds(any(), any(), anyInt())).thenReturn(List.of());
            when(transactionMapper.selectLatePaidCheckTimeoutIds(any(), anyInt())).thenReturn(List.of(96L));
            when(transactionMapper.selectById(96L)).thenReturn(tx(96L, PayStatus.TIMEOUT));
            when(transactionMapper.selectByIdForUpdate(96L)).thenReturn(tx(96L, PayStatus.TIMEOUT));
            when(wechatPayClient.queryByOutTradeNo(OUT_TRADE_NO)).thenReturn(
                new QueryResult(OUT_TRADE_NO, TXN_ID, "SUCCESS", 3000L, null, "{\"trade_state\":\"SUCCESS\"}"));

            LocalDateTime before = LocalDateTime.now();
            queryService.scanAndReconcile();

            ArgumentCaptor<LocalDateTime> windowStart = ArgumentCaptor.forClass(LocalDateTime.class);
            verify(transactionMapper).selectLatePaidCheckTimeoutIds(windowStart.capture(), anyInt());
            long hours = Duration.between(windowStart.getValue(), before).toMinutes();
            assertTrue(hours >= 24 * 60 - 1 && hours <= 24 * 60 + 1, "窗口 24h：" + hours + " min");

            assertNothingAdvanced();
            verify(wechatPayClient, never()).closeOrder(anyString());
            assertLatePaidLog(capturedLogs(1).get(0));
        }
    }

    @Test
    @DisplayName("查单任务：timeout 单微信侧仍 NOTPAY（修复前的在途单）→ 补关微信单，不记 late_paid")
    void scan_timeoutOrderStillNotPaid_closesWechatOrder() {
        try (MockedStatic<TenantHelper> ignored = tenantIgnorePassThrough()) {
            when(transactionMapper.selectReconcilePendingIds(any(), any(), anyInt())).thenReturn(List.of());
            when(transactionMapper.selectLatePaidCheckTimeoutIds(any(), anyInt())).thenReturn(List.of(97L));
            when(transactionMapper.selectById(97L)).thenReturn(tx(97L, PayStatus.TIMEOUT));
            when(wechatPayClient.queryByOutTradeNo(OUT_TRADE_NO)).thenReturn(
                new QueryResult(OUT_TRADE_NO, null, "NOTPAY", null, null, "{\"trade_state\":\"NOTPAY\"}"));

            queryService.scanAndReconcile();

            verify(wechatPayClient).closeOrder(OUT_TRADE_NO);
            verify(callbackLogMapper, never()).insert(any(GzPayCallbackLog.class));
            verify(transactionMapper, never()).selectByIdForUpdate(anyLong());
            assertNothingAdvanced();
        }
    }

    @Test
    @DisplayName("查单任务：timeout 单核查按 10 分钟节流（任务 90s 一轮，不每轮都查微信）")
    void scan_timeoutCheckThrottled() {
        try (MockedStatic<TenantHelper> ignored = tenantIgnorePassThrough()) {
            when(transactionMapper.selectReconcilePendingIds(any(), any(), anyInt())).thenReturn(List.of());
            when(transactionMapper.selectLatePaidCheckTimeoutIds(any(), anyInt())).thenReturn(List.of());

            queryService.scanAndReconcile();
            queryService.scanAndReconcile();

            verify(transactionMapper, times(2)).selectReconcilePendingIds(any(), any(), anyInt());
            verify(transactionMapper, times(1)).selectLatePaidCheckTimeoutIds(any(), anyInt());
        }
    }

    // ============================================================
    //  4. admin 支付流水「迟到付款」筛选 + 行标记
    // ============================================================

    @Test
    @DisplayName("admin 列表：latePaid=true → SQL 带 late_paid 子查询；命中行 latePaid=true，其余 false")
    @SuppressWarnings("unchecked")
    void adminList_latePaidFilterAndRowFlag() {
        GzPayTransactionVO late = new GzPayTransactionVO();
        late.setOutTradeNo(OUT_TRADE_NO);
        GzPayTransactionVO normal = new GzPayTransactionVO();
        normal.setOutTradeNo("PINDOU-20261005-000009");
        Page<GzPayTransactionVO> page = new Page<>(1, 10);
        page.setRecords(List.of(late, normal));
        page.setTotal(2);
        ArgumentCaptor<Wrapper<GzPayTransaction>> wrapperCap = ArgumentCaptor.forClass(Wrapper.class);
        when(transactionMapper.selectVoPage(any(), wrapperCap.capture())).thenReturn(page);
        GzPayCallbackLog log = new GzPayCallbackLog();
        log.setOutTradeNo(OUT_TRADE_NO);
        log.setProcessStatus("late_paid");
        when(callbackLogMapper.selectList(any(Wrapper.class))).thenReturn(List.of(log));

        GzPayTransactionQueryBo query = new GzPayTransactionQueryBo();
        query.setLatePaid(true);
        TableDataInfo<GzPayTransactionVO> result = service.selectPageList(query, new PageQuery(10, 1));

        String sql = wrapperCap.getValue().getSqlSegment();
        assertTrue(sql.contains("gz_pay_callback_log") && sql.contains("late_paid"), "筛选子查询：" + sql);
        assertTrue(result.getRows().get(0).getLatePaid());
        assertFalse(result.getRows().get(1).getLatePaid());

        // 不勾筛选 → 不带子查询
        service.selectPageList(new GzPayTransactionQueryBo(), new PageQuery(10, 1));
        assertFalse(wrapperCap.getValue().getSqlSegment().contains("late_paid"));
    }
}
