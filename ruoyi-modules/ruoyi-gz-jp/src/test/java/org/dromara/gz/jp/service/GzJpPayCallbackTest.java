package org.dromara.gz.jp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.gz.common.pay.config.WechatPayProperties;
import org.dromara.gz.common.pay.domain.bo.CreateOrderBo;
import org.dromara.gz.common.pay.domain.entity.GzPayCallbackLog;
import org.dromara.gz.common.pay.domain.entity.GzPayTransaction;
import org.dromara.gz.common.pay.domain.vo.MpPayParamsVO;
import org.dromara.gz.common.pay.enums.PayBusinessType;
import org.dromara.gz.common.pay.enums.PayStatus;
import org.dromara.gz.common.pay.mapper.GzPayCallbackLogMapper;
import org.dromara.gz.common.pay.mapper.GzPayChannelMapper;
import org.dromara.gz.common.pay.mapper.GzPayRefundMapper;
import org.dromara.gz.common.pay.mapper.GzPayTransactionMapper;
import org.dromara.gz.common.pay.service.impl.GzPayTransactionServiceImpl;
import org.dromara.gz.common.pay.service.internal.IWechatPayClient.NotifyContext;
import org.dromara.gz.common.pay.service.internal.MockWechatPayClient;
import org.dromara.gz.common.pay.service.internal.PayAppidResolver;
import org.dromara.gz.common.pay.service.internal.PayOrderNoGenerator;
import org.dromara.gz.common.pay.service.spi.PayCallbackDispatcher;
import org.dromara.gz.common.pay.service.spi.PayCallbackHandler;
import org.dromara.gz.common.wechat.WxAppResolver;
import org.dromara.gz.common.wechat.WxMiniappProperties;
import org.dromara.gz.jp.domain.entity.GzJpOrder;
import org.dromara.gz.jp.domain.entity.GzJpOrderItem;
import org.dromara.gz.jp.domain.enums.GzJpFulfillStatus;
import org.dromara.gz.jp.domain.enums.GzJpOrderStatus;
import org.dromara.gz.jp.mapper.GzJpOrderItemMapper;
import org.dromara.gz.jp.mapper.GzJpOrderMapper;
import org.dromara.gz.jp.service.callback.JpPayCallbackHandler;
import org.dromara.gz.jp.service.impl.GzJpOrderServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * 拼团支付回调全链路单测（GZ-JP-105，FLOW:F-JP-02.step5 → step6）。
 *
 * <p><b>accept 第 3 条直接跑本类</b>（「支付回调后订单转 paid 且全部行进入购买中」）。</p>
 *
 * <p>用<b>真实</b> {@link MockWechatPayClient}（走 mock V3 报文解析路径）+ <b>真实</b>
 * {@link PayCallbackDispatcher}（真按 business_type 路由）+ <b>真实</b> {@link JpPayCallbackHandler}
 * + <b>真实</b> {@link GzJpOrderServiceImpl}，只有 mapper 用 in-memory 假实现模拟 DB
 * （含 UNIQUE / 乐观锁 / <b>状态守卫</b>）—— 幂等靠的就是那两道状态守卫，用 mock 桩替掉就测了个寂寞。</p>
 *
 * <pre>
 *   createBusinessOrder(jp, business_order_no=JPO-…) → mock 调起
 *     → mock V3 回调 body → handlePaymentNotify 乐观锁 pending→paid
 *     → PayCallbackDispatcher 按 business_type=jp 路由到真 JpPayCallbackHandler
 *     → GzJpOrderService.onPaid 行锁 → markPaid(created→paid) → 全部商品行 purchasing
 *   重复回调 → 双层幂等（PAY 侧 duplicated + jp 侧 markPaid affected=0）
 * </pre>
 *
 * @author kevin-coder (sensenran-guzi · GZ-JP-105)
 */
@Tag("dev")
@DisplayName("GZ-JP-105 支付回调（jp SPI 路由 → 订单 paid → 全部行购买中 → 幂等）")
@ExtendWith(MockitoExtension.class)
class GzJpPayCallbackTest {

    private static final Long USER_ID = 2001L;

    @Mock
    private GzPayTransactionMapper payTxMapper;
    @Mock
    private GzPayCallbackLogMapper callbackLogMapper;
    @Mock
    private GzPayRefundMapper refundMapper;
    @Mock
    private GzJpOrderMapper jpOrderMapper;
    @Mock
    private GzJpOrderItemMapper jpItemMapper;
    @Mock
    private org.dromara.gz.common.pay.service.IGzPayShippingService shippingService;

    private MockWechatPayClient mockClient;
    private GzPayTransactionServiceImpl payService;
    private GzJpOrderServiceImpl orderService;
    private PayCallbackDispatcher dispatcher;

    /** in-memory：gz_pay_transaction（out_trade_no → 行） */
    private final Map<String, GzPayTransaction> payDb = new HashMap<>();
    /** in-memory：gz_jp_order（order_no → 行） */
    private final Map<String, GzJpOrder> orderDb = new HashMap<>();
    /** in-memory：gz_jp_order_item（order_id → 行列表） */
    private final Map<Long, List<GzJpOrderItem>> itemDb = new HashMap<>();
    private final List<GzPayCallbackLog> callbackLogs = new ArrayList<>();
    private long payIdSeq = 7000L;

    @BeforeEach
    void setUp() {
        WechatPayProperties props = new WechatPayProperties();
        props.setClientMode("mock");
        mockClient = new MockWechatPayClient(props);

        // 真实 jp 订单服务（回调路径只用到两个 mapper，其余 collaborator 本测试不触及）
        orderService = new GzJpOrderServiceImpl(jpOrderMapper, jpItemMapper, null, null, null,
            null, null, null, null, null, new ObjectMapper());

        // SPI：真实 dispatcher + 真实 jp handler（启动期路由表由 validate() 建）
        JpPayCallbackHandler jpHandler = new JpPayCallbackHandler(orderService);
        dispatcher = new PayCallbackDispatcher(List.of(jpHandler));
        dispatcher.validate();

        // PayOrderNoGenerator 用内存版 redisson（按 key 从 0 自增）
        org.redisson.api.RedissonClient redissonMock = mock(org.redisson.api.RedissonClient.class);
        Map<String, AtomicLong> seqCounters = new ConcurrentHashMap<>();
        lenient().when(redissonMock.getAtomicLong(anyString())).thenAnswer(inv -> {
            AtomicLong backing = seqCounters.computeIfAbsent(inv.getArgument(0, String.class), k -> new AtomicLong(0L));
            org.redisson.api.RAtomicLong ral = mock(org.redisson.api.RAtomicLong.class);
            lenient().when(ral.get()).thenAnswer(i -> backing.get());
            lenient().when(ral.compareAndSet(anyLong(), anyLong()))
                .thenAnswer(i -> backing.compareAndSet(i.getArgument(0, Long.class), i.getArgument(1, Long.class)));
            lenient().when(ral.incrementAndGet()).thenAnswer(i -> backing.incrementAndGet());
            return ral;
        });
        PayOrderNoGenerator generator = new PayOrderNoGenerator(payTxMapper, refundMapper, redissonMock);

        @SuppressWarnings("unchecked")
        ObjectProvider<PayCallbackDispatcher> dispatcherProvider = mock(ObjectProvider.class);
        lenient().when(dispatcherProvider.getObject()).thenReturn(dispatcher);
        PayAppidResolver appidResolver = new PayAppidResolver(
            new WxAppResolver(new WxMiniappProperties()), mock(GzPayChannelMapper.class), props);
        payService = new GzPayTransactionServiceImpl(payTxMapper, callbackLogMapper, generator, mockClient,
            props, shippingService, dispatcherProvider, appidResolver);

        wirePayMappers();
        wireJpMappers();
    }

    private void wirePayMappers() {
        lenient().when(payTxMapper.selectMaxDailySeq(anyString())).thenReturn(0L);
        lenient().when(payTxMapper.insert(any(GzPayTransaction.class))).thenAnswer(inv -> {
            GzPayTransaction tx = inv.getArgument(0);
            tx.setId(payIdSeq++);
            payDb.put(tx.getOutTradeNo(), tx);
            return 1;
        });
        lenient().when(payTxMapper.markPending(any(), anyString())).thenAnswer(inv -> {
            GzPayTransaction tx = payById(inv.getArgument(0));
            if (tx != null && PayStatus.CREATED.equals(tx.getStatus())) {
                tx.setStatus(PayStatus.PENDING);
                tx.setPrepayId(inv.getArgument(1));
                tx.setVersion(tx.getVersion() + 1);
                return 1;
            }
            return 0;
        });
        lenient().when(payTxMapper.selectByOutTradeNo(anyString()))
            .thenAnswer(inv -> payDb.get((String) inv.getArgument(0)));
        lenient().when(payTxMapper.markPaid(any(), any(), anyString(), any(), any())).thenAnswer(inv -> {
            GzPayTransaction tx = payById(inv.getArgument(0));
            Integer version = inv.getArgument(1);
            if (tx != null && PayStatus.PENDING.equals(tx.getStatus()) && tx.getVersion().equals(version)) {
                tx.setStatus(PayStatus.PAID);
                tx.setTransactionId(inv.getArgument(2));
                tx.setFeeCent(inv.getArgument(3));
                tx.setPaidTime(inv.getArgument(4));
                tx.setVersion(tx.getVersion() + 1);
                return 1;
            }
            return 0;
        });
        lenient().when(callbackLogMapper.insert(any(GzPayCallbackLog.class))).thenAnswer(inv -> {
            callbackLogs.add(inv.getArgument(0));
            return 1;
        });
    }

    /** in-memory gz_jp_order / gz_jp_order_item —— ★ 状态守卫照搬 SQL 的 WHERE，幂等才测得出来。 */
    private void wireJpMappers() {
        lenient().when(jpOrderMapper.selectByOrderNoForUpdate(anyString()))
            .thenAnswer(inv -> orderDb.get((String) inv.getArgument(0)));
        lenient().when(jpOrderMapper.markPaid(any(), any(), any())).thenAnswer(inv -> {
            GzJpOrder o = orderById(inv.getArgument(0));
            // WHERE business_status = 'created'
            if (o != null && GzJpOrderStatus.CREATED.getCode().equals(o.getBusinessStatus())) {
                o.setBusinessStatus(GzJpOrderStatus.PAID.getCode());
                o.setPayTransactionId(inv.getArgument(1));
                o.setPaidTime(inv.getArgument(2));
                o.setVersion(o.getVersion() + 1);
                return 1;
            }
            return 0;
        });
        lenient().when(jpItemMapper.activatePurchasing(any())).thenAnswer(inv -> {
            Long orderId = inv.getArgument(0);
            int affected = 0;
            // WHERE fulfill_status = 'purchasing'（已推进到下游状态的行绝不被打回）
            for (GzJpOrderItem item : itemDb.getOrDefault(orderId, List.of())) {
                if (GzJpFulfillStatus.PURCHASING.getCode().equals(item.getFulfillStatus())) {
                    item.setVersion(item.getVersion() + 1);
                    affected++;
                }
            }
            return affected;
        });
    }

    // ============================================================
    //  ★ accept 第 3 条
    // ============================================================

    @Test
    @DisplayName("★ 全链路：建单(jp) → mock V3 回调 → SPI 路由 jp → 订单 paid + 全部 3 行进入购买中")
    void jpFullChain_orderPaidAndAllItemsPurchasing() {
        String orderNo = "JPO-20260807-000001";
        GzJpOrder order = seedCreatedOrder(orderNo, 58000L);
        seedItems(order.getId(), 3);

        // ① 建单（business_type=jp，businessOrderNo=order_no）
        MpPayParamsVO params = payService.createBusinessOrder(CreateOrderBo.builder()
            .businessType(PayBusinessType.JP)
            .businessOrderNo(orderNo)
            .amountCent(58000L)
            .openid("openid_jp_tester")
            .userId(USER_ID)
            .description("谷子宇宙拼团 - 柯南 吧唧 A赏 等 3 款")
            .build());
        String outTradeNo = params.getOutTradeNo();
        assertTrue(outTradeNo.startsWith("JPO-"), "out_trade_no 前缀 JPO-：" + outTradeNo);

        GzPayTransaction afterCreate = payDb.get(outTradeNo);
        assertEquals(PayStatus.PENDING, afterCreate.getStatus());
        assertEquals("jp", afterCreate.getBusinessType());
        assertEquals(orderNo, afterCreate.getBusinessOrderNo());
        // 支付前：订单仍 created
        assertEquals(GzJpOrderStatus.CREATED.getCode(), orderDb.get(orderNo).getBusinessStatus());
        assertNull(orderDb.get(orderNo).getPaidTime());
        System.out.println("[AC3] 建单 out_trade_no=" + outTradeNo + " business_type=jp 订单初态=created");

        // ② mock V3 回调
        String mockTxnId = "mock_wx_txn_" + outTradeNo;
        NotifyContext ctx = new NotifyContext("0", "mock_nonce", "mock_sig", "mock_serial",
            mockClient.buildMockCallbackBody(outTradeNo, mockTxnId, 58000L));
        assertTrue(payService.handlePaymentNotify(ctx));

        // ③ 终态
        GzPayTransaction paidTx = payDb.get(outTradeNo);
        assertEquals(PayStatus.PAID, paidTx.getStatus());
        assertEquals(mockTxnId, paidTx.getTransactionId());

        GzJpOrder paidOrder = orderDb.get(orderNo);
        assertEquals(GzJpOrderStatus.PAID.getCode(), paidOrder.getBusinessStatus(), "订单 → paid");
        assertEquals(paidTx.getId(), paidOrder.getPayTransactionId(),
            "★ pay_transaction_id 存的是 gz_pay_transaction.id（本地流水行主键），不是微信交易号字符串");
        assertNotNull(paidOrder.getPaidTime(), "paid_time 写入");

        // ★ 全部商品行进入购买中
        List<GzJpOrderItem> items = itemDb.get(paidOrder.getId());
        assertEquals(3, items.size());
        assertTrue(items.stream().allMatch(i -> GzJpFulfillStatus.PURCHASING.getCode().equals(i.getFulfillStatus())),
            "全部 3 行 fulfill_status=purchasing");
        assertTrue(items.stream().allMatch(i -> i.getVersion() == 1), "3 行都被本次回调激活过（version 各 +1）");
        System.out.println("[AC3] 回调后 订单=" + paidOrder.getBusinessStatus()
            + " pay_transaction_id=" + paidOrder.getPayTransactionId()
            + " 商品行=" + items.stream().map(GzJpOrderItem::getFulfillStatus).toList());
    }

    @Test
    @DisplayName("★ 幂等：同一笔回调发两次 → 订单仍 paid，商品行不被二次激活（version 不再 +1）")
    void duplicateCallback_isIdempotent() {
        String orderNo = "JPO-20260807-000011";
        GzJpOrder order = seedCreatedOrder(orderNo, 12800L);
        seedItems(order.getId(), 2);

        MpPayParamsVO params = payService.createBusinessOrder(CreateOrderBo.builder()
            .businessType(PayBusinessType.JP).businessOrderNo(orderNo).amountCent(12800L)
            .openid("openid_jp_tester").userId(USER_ID).description("谷子宇宙拼团").build());
        String outTradeNo = params.getOutTradeNo();
        NotifyContext ctx = new NotifyContext("0", "mock_nonce", "mock_sig", "mock_serial",
            mockClient.buildMockCallbackBody(outTradeNo, "mock_wx_txn_" + outTradeNo, 12800L));

        assertTrue(payService.handlePaymentNotify(ctx), "首次回调");
        LocalDateTime firstPaidTime = orderDb.get(orderNo).getPaidTime();
        List<Integer> versionsAfterFirst = itemDb.get(order.getId()).stream().map(GzJpOrderItem::getVersion).toList();

        assertTrue(payService.handlePaymentNotify(ctx), "重复回调仍返回成功（幂等，不让微信一直重试）");

        GzJpOrder after = orderDb.get(orderNo);
        assertEquals(GzJpOrderStatus.PAID.getCode(), after.getBusinessStatus(), "仍 paid");
        assertEquals(firstPaidTime, after.getPaidTime(), "paid_time 未被二次覆盖");
        assertEquals(1, after.getVersion(), "订单只被推进过一次");
        assertEquals(versionsAfterFirst, itemDb.get(order.getId()).stream().map(GzJpOrderItem::getVersion).toList(),
            "★ 商品行未被二次激活");
        System.out.println("[幂等] 两次回调后 订单 version=" + after.getVersion()
            + " 行 version=" + versionsAfterFirst + " callback_logs=" + callbackLogs.size());
    }

    @Test
    @DisplayName("★ 幂等第二道：已推进到「日本仓库已发货」的行，回调重放也不会被打回「购买中」")
    void replayCallback_doesNotRegressAdvancedItems() {
        String orderNo = "JPO-20260807-000021";
        GzJpOrder order = seedCreatedOrder(orderNo, 30000L);
        seedItems(order.getId(), 2);
        // 模拟：支付后店员已把第 1 行推到 jp_shipped（GZ-JP-106 的事）
        itemDb.get(order.getId()).get(0).setFulfillStatus(GzJpFulfillStatus.JP_SHIPPED.getCode());

        // 直接调 service.onPaid（等价于回调重放且订单被人工改回 created 的极端情形）
        GzPayTransaction txn = new GzPayTransaction();
        txn.setId(9999L);
        txn.setBusinessOrderNo(orderNo);
        txn.setOutTradeNo("JPO-20260807-000022");
        txn.setPaidTime(LocalDateTime.now());
        orderService.onPaid(txn);

        List<GzJpOrderItem> items = itemDb.get(order.getId());
        assertEquals(GzJpFulfillStatus.JP_SHIPPED.getCode(), items.get(0).getFulfillStatus(),
            "已发货的行保持原状，绝不被打回购买中");
        assertEquals(GzJpFulfillStatus.PURCHASING.getCode(), items.get(1).getFulfillStatus());
    }

    @Test
    @DisplayName("SPI 路由表：business_type=jp 命中 JpPayCallbackHandler（新业务接入零改动 GZ-PAY）")
    void spiRouteRegistered() {
        PayCallbackHandler handler = dispatcher.resolve(PayBusinessType.JP);
        assertNotNull(handler, "jp 已注册进路由表");
        assertInstanceOf(JpPayCallbackHandler.class, handler);
        assertEquals("jp", handler.supportedBusinessType());
        // ★ 支付回调不上报微信发货信息（拼团支付当下没有任何可上报的发货事实，见 handler 类注释）
        assertTrue(handler.buildShippingInfo(new GzPayTransaction()).isEmpty(), "不接订单中心发货上报");
    }

    @Test
    @DisplayName("订单不存在 → 抛异常让整笔回调事务回滚（微信重试 + 主动查单兜底），不静默吞")
    void orderMissing_throwsToRollback() {
        GzPayTransaction txn = new GzPayTransaction();
        txn.setBusinessOrderNo("JPO-20260807-999999");
        txn.setOutTradeNo("JPO-20260807-999999");

        ServiceException ex = assertThrows(ServiceException.class, () -> orderService.onPaid(txn));
        assertTrue(ex.getMessage().contains("拼团订单不存在"), ex.getMessage());
    }

    @Test
    @DisplayName("business_order_no 为空 → 记日志跳过，不抛错（避免一笔脏数据把回调卡死重试）")
    void blankBusinessOrderNo_skips() {
        GzPayTransaction txn = new GzPayTransaction();
        txn.setOutTradeNo("JPO-20260807-000099");
        orderService.onPaid(txn);
        assertTrue(orderDb.isEmpty() || orderDb.values().stream()
            .noneMatch(o -> GzJpOrderStatus.PAID.getCode().equals(o.getBusinessStatus())));
    }

    // ============================================================
    //  fixtures
    // ============================================================

    private GzPayTransaction payById(Long id) {
        return payDb.values().stream().filter(t -> id.equals(t.getId())).findFirst().orElse(null);
    }

    private GzJpOrder orderById(Long id) {
        return orderDb.values().stream().filter(o -> id.equals(o.getId())).findFirst().orElse(null);
    }

    private GzJpOrder seedCreatedOrder(String orderNo, long amountCent) {
        GzJpOrder o = new GzJpOrder();
        o.setId(8000L + orderDb.size());
        o.setOrderNo(orderNo);
        o.setUserId(USER_ID);
        o.setTotalAmountCent(amountCent);
        o.setBusinessStatus(GzJpOrderStatus.CREATED.getCode());
        o.setAddressSnapshotJson("{\"recipient\":\"张三\"}");
        o.setVersion(0);
        orderDb.put(orderNo, o);
        return o;
    }

    private void seedItems(Long orderId, int n) {
        List<GzJpOrderItem> items = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            GzJpOrderItem item = new GzJpOrderItem();
            item.setId(60_000L + i);
            item.setOrderId(orderId);
            item.setUserId(USER_ID);
            item.setProductId((long) (i + 1));
            item.setQty(1);
            item.setUnitPriceCent(12800L);
            item.setAmountCent(12800L);
            item.setSource("batch");
            // 建单即落履约起点（列默认值）；回调再显式激活一次
            item.setFulfillStatus(GzJpFulfillStatus.PURCHASING.getCode());
            item.setVersion(0);
            items.add(item);
        }
        itemDb.put(orderId, items);
    }
}
