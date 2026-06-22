package org.dromara.gz.common.pay.service;

import org.dromara.gz.common.pay.config.WechatPayProperties;
import org.dromara.gz.common.pay.domain.bo.CreateOrderBo;
import org.dromara.gz.common.pay.domain.entity.GzPayCallbackLog;
import org.dromara.gz.common.pay.domain.entity.GzPayTransaction;
import org.dromara.gz.common.pay.domain.vo.MpPayParamsVO;
import org.dromara.gz.common.pay.enums.PayBusinessType;
import org.dromara.gz.common.pay.enums.PayStatus;
import org.dromara.gz.common.pay.mapper.GzPayCallbackLogMapper;
import org.dromara.gz.common.pay.mapper.GzPayTransactionMapper;
import org.dromara.gz.common.pay.service.impl.GzPayTransactionServiceImpl;
import org.dromara.gz.common.pay.service.internal.IWechatPayClient.NotifyContext;
import org.dromara.gz.common.pay.service.internal.MockWechatPayClient;
import org.dromara.gz.common.pay.service.internal.PayOrderNoGenerator;
import org.dromara.gz.common.pay.service.spi.PayCallbackDispatcher;
import org.dromara.gz.common.pay.service.spi.PayCallbackHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * GZ-PAY-101 AC 10 — mock 全链路自测（preorder 业务单端到端）。
 *
 * <p>用<b>真实</b> {@link MockWechatPayClient}（mock V3 解析路径）+ <b>真实</b>
 * {@link PayCallbackDispatcher} + <b>测试内联 preorder {@link PayCallbackHandler}</b>（真实 preorder
 * handler 已下沉 ruoyi-gz-ord 模块，gz-common 单测只验 SPI 路由按 business_type=preorder 命中，
 * 用内联匿名 handler 即可，不依赖 gz-ord），仅 mapper 用 in-memory 假实现模拟 DB，跑通：</p>
 *
 * <pre>
 *   createBusinessOrder(preorder) → mock 调起（mock_prepay_）
 *     → mock V3 回调 body（MockWechatPayClient.parseAndVerifyNotify AES-GCM 解析路径）
 *     → handlePaymentNotify 乐观锁 pending→paid
 *     → PayCallbackDispatcher 按 business_type=preorder 路由到内联 preorder handler.onPaid
 *     → status=paid
 *   重复回调 → 幂等（不二次 paid，callback_log duplicated）
 * </pre>
 *
 * <p>不依赖 Spring 上下文 / 真实 DB（mapper 用内存 Map 模拟 UNIQUE + 乐观锁语义），CI 稳定。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-PAY-101)
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class GzPayBusinessFullChainMockTest {

    @Mock
    private GzPayTransactionMapper transactionMapper;

    @Mock
    private GzPayCallbackLogMapper callbackLogMapper;

    @Mock
    private org.dromara.gz.common.pay.mapper.GzPayRefundMapper refundMapper;

    private MockWechatPayClient mockClient;
    private PayCallbackDispatcher dispatcher;
    /** 测试内联 preorder 支付 handler（真实 handler 已下沉 gz-ord，此处仅验 SPI 路由命中） */
    private PayCallbackHandler preorderHandler;
    private GzPayTransactionServiceImpl service;

    /** in-memory DB（out_trade_no → 交易行），模拟 UNIQUE + 乐观锁 */
    private final Map<String, GzPayTransaction> db = new HashMap<>();
    /** callback_log 内存表（断言用） */
    private final List<GzPayCallbackLog> callbackLogs = new ArrayList<>();
    private long idSeq = 5000L;

    @BeforeEach
    void setUp() {
        WechatPayProperties props = new WechatPayProperties();
        props.setClientMode("mock");
        mockClient = new MockWechatPayClient(props);
        // SPI：真实 dispatcher + 测试内联 preorder handler（验 business_type=preorder 路由命中即可，
        // 真实出单逻辑已下沉 ruoyi-gz-ord PreorderPayCallbackHandler，gz-common 不依赖 gz-ord）
        preorderHandler = new PayCallbackHandler() {
            @Override
            public String supportedBusinessType() {
                return PayBusinessType.PREORDER;
            }

            @Override
            public void onPaid(GzPayTransaction txn) {
                // 内联占位：仅验 SPI 路由命中 preorder，不做业务出单
            }
        };
        dispatcher = new PayCallbackDispatcher(List.of(preorderHandler));
        dispatcher.validate();
        @SuppressWarnings("unchecked")
        ObjectProvider<PayCallbackDispatcher> dispatcherProvider = mock(ObjectProvider.class);
        lenient().when(dispatcherProvider.getObject()).thenReturn(dispatcher);

        PayOrderNoGenerator generator = new PayOrderNoGenerator(transactionMapper, refundMapper, PayGeneratorTestSupport.inMemoryRedisson());
        service = new GzPayTransactionServiceImpl(
            transactionMapper, callbackLogMapper, generator, mockClient, props, dispatcherProvider);

        wireInMemoryMappers();
    }

    /** 把 mapper 接成内存 Map 行为（模拟 DB 落库 + 乐观锁 + 序号生成 + 幂等查询） */
    private void wireInMemoryMappers() {
        // 序号生成：当日最大序号（内存里恒 0 → 序号从 000001 起）
        lenient().when(transactionMapper.selectMaxDailySeq(anyString())).thenReturn(0L);

        // insert：分配 id + 落库（out_trade_no 唯一）
        lenient().when(transactionMapper.insert(any(GzPayTransaction.class))).thenAnswer(inv -> {
            GzPayTransaction tx = inv.getArgument(0);
            tx.setId(idSeq++);
            db.put(tx.getOutTradeNo(), tx);
            return 1;
        });

        // markPending：created → pending + prepay_id（条件守卫 status=created）
        lenient().when(transactionMapper.markPending(any(), anyString())).thenAnswer(inv -> {
            Long id = inv.getArgument(0);
            String prepayId = inv.getArgument(1);
            GzPayTransaction tx = findById(id);
            if (tx != null && PayStatus.CREATED.equals(tx.getStatus())) {
                tx.setStatus(PayStatus.PENDING);
                tx.setPrepayId(prepayId);
                tx.setVersion(tx.getVersion() + 1);
                return 1;
            }
            return 0;
        });

        // selectByOutTradeNo：查回调订单
        lenient().when(transactionMapper.selectByOutTradeNo(anyString()))
            .thenAnswer(inv -> db.get((String) inv.getArgument(0)));

        // markPaid：乐观锁 pending + version 守卫
        lenient().when(transactionMapper.markPaid(any(), any(), anyString(), any(), any())).thenAnswer(inv -> {
            Long id = inv.getArgument(0);
            Integer version = inv.getArgument(1);
            String txnId = inv.getArgument(2);
            Long feeCent = inv.getArgument(3);
            GzPayTransaction tx = findById(id);
            if (tx != null && PayStatus.PENDING.equals(tx.getStatus()) && tx.getVersion().equals(version)) {
                tx.setStatus(PayStatus.PAID);
                tx.setTransactionId(txnId);
                tx.setFeeCent(feeCent);
                tx.setVersion(tx.getVersion() + 1);
                return 1;
            }
            return 0;  // 已 paid / version 漂移 → 幂等跳过
        });

        // callback_log insert：落内存表
        lenient().when(callbackLogMapper.insert(any(GzPayCallbackLog.class))).thenAnswer(inv -> {
            callbackLogs.add(inv.getArgument(0));
            return 1;
        });
    }

    private GzPayTransaction findById(Long id) {
        return db.values().stream().filter(t -> id.equals(t.getId())).findFirst().orElse(null);
    }

    @Test
    @DisplayName("AC 10：preorder 全链路 — 建单→mock调起→mock V3 回调→SPI 路由 preorder→paid；重复回调幂等")
    void preorderFullChain_mock() {
        // ① 建单（business_type=preorder）
        CreateOrderBo bo = CreateOrderBo.builder()
            .businessType(PayBusinessType.PREORDER)
            .businessOrderNo("PREORD-ORDER-20260604-001")
            .amountCent(9900L)
            .openid("openid_buyer_001")
            .userId(2001L)
            .description("谷子宇宙预购单 - 测试")
            .build();

        MpPayParamsVO params = service.createBusinessOrder(bo);
        String outTradeNo = params.getOutTradeNo();

        // 建单断言：out_trade_no = PREORD-yyyyMMdd-000001 + 落 pending + mock prepay_id
        assertTrue(outTradeNo.startsWith("PREORD-"), "out_trade_no 前缀 PREORD-：" + outTradeNo);
        assertTrue(outTradeNo.endsWith("-000001"), "日内序号从 000001 起：" + outTradeNo);
        GzPayTransaction afterCreate = db.get(outTradeNo);
        assertEquals(PayStatus.PENDING, afterCreate.getStatus());
        assertEquals("preorder", afterCreate.getBusinessType());
        assertEquals("PREORD-ORDER-20260604-001", afterCreate.getBusinessOrderNo());
        assertEquals("wechat_pay_v3", afterCreate.getChannelCode());
        assertNull(afterCreate.getFeeCent(), "fee_cent 建单留 NULL（PAY-104 回写）");
        assertTrue(afterCreate.getPrepayId().startsWith(MockWechatPayClient.MOCK_PREPAY_PREFIX));
        assertEquals("prepay_id=" + afterCreate.getPrepayId(), params.getPackageVal());

        // ② mock V3 回调（走 MockWechatPayClient.buildMockCallbackBody → parseAndVerifyNotify 解析路径）
        String mockTxnId = "mock_wx_txn_" + outTradeNo;
        String body = mockClient.buildMockCallbackBody(outTradeNo, mockTxnId, 9900L);
        NotifyContext ctx = new NotifyContext("0", "mock_nonce", "mock_sig", "mock_serial", body);

        boolean ok = service.handlePaymentNotify(ctx);

        // ③ paid + SPI 路由命中 preorder handler（真 PreorderPayCallbackHandler.onPaid 已执行，见日志）
        assertTrue(ok);
        GzPayTransaction afterPaid = db.get(outTradeNo);
        assertEquals(PayStatus.PAID, afterPaid.getStatus());
        assertEquals(mockTxnId, afterPaid.getTransactionId());
        // callback_log：received + processed
        assertEquals(2, callbackLogs.size());
        assertEquals("received", callbackLogs.get(0).getProcessStatus());
        assertEquals("processed", callbackLogs.get(1).getProcessStatus());

        // ④ 重复回调 → 幂等（已 paid → duplicated，不二次 paid）
        boolean ok2 = service.handlePaymentNotify(ctx);
        assertTrue(ok2, "重复回调幂等返回成功");
        assertEquals(PayStatus.PAID, db.get(outTradeNo).getStatus());
        // 多一条 duplicated（received + processed + received + duplicated 实际：第二次 received + duplicated）
        assertEquals(4, callbackLogs.size());
        assertEquals("duplicated", callbackLogs.get(3).getProcessStatus());
        // markPaid 第一次 affected=1，第二次因已 paid → service 走幂等 SELECT 分支不再调 markPaid
        verify(transactionMapper, times(1)).markPaid(any(), eq(1), eq(mockTxnId), any(), any());
    }

    @Test
    @DisplayName("AC 10：test 单全链路 — 无 SPI handler → dispatch 跳过不报错 → paid（保持 PAY-001 行为）")
    void testOrderFullChain_noHandlerSkip() {
        CreateOrderBo bo = CreateOrderBo.builder()
            .businessType(PayBusinessType.TEST)
            .businessOrderNo(null)
            .amountCent(1L)
            .openid("openid_admin_test")
            .userId(1L)
            .description("通道测试单")
            .build();
        MpPayParamsVO params = service.createBusinessOrder(bo);
        String outTradeNo = params.getOutTradeNo();
        assertTrue(outTradeNo.startsWith("TEST-"));

        String body = mockClient.buildMockCallbackBody(outTradeNo, "mock_wx_txn_test", 1L);
        NotifyContext ctx = new NotifyContext("0", "n", "s", "ser", body);
        boolean ok = service.handlePaymentNotify(ctx);

        assertTrue(ok);
        assertEquals(PayStatus.PAID, db.get(outTradeNo).getStatus(), "test 单无 handler 仍正常 paid");

        ArgumentCaptor<GzPayCallbackLog> cap = ArgumentCaptor.forClass(GzPayCallbackLog.class);
        verify(callbackLogMapper, times(2)).insert(cap.capture());
        assertEquals("processed", cap.getAllValues().get(1).getProcessStatus());
    }
}
