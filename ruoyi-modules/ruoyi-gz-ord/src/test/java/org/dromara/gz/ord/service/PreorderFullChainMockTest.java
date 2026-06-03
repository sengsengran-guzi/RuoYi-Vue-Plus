package org.dromara.gz.ord.service;

import org.dromara.gz.common.pay.config.WechatPayProperties;
import org.dromara.gz.common.pay.domain.bo.CreateOrderBo;
import org.dromara.gz.common.pay.domain.entity.GzPayCallbackLog;
import org.dromara.gz.common.pay.domain.entity.GzPayTransaction;
import org.dromara.gz.common.pay.domain.vo.MpPayParamsVO;
import org.dromara.gz.common.pay.enums.PayBusinessType;
import org.dromara.gz.common.pay.enums.PayStatus;
import org.dromara.gz.common.pay.mapper.GzPayCallbackLogMapper;
import org.dromara.gz.common.pay.mapper.GzPayRefundMapper;
import org.dromara.gz.common.pay.mapper.GzPayTransactionMapper;
import org.dromara.gz.common.pay.service.impl.GzPayTransactionServiceImpl;
import org.dromara.gz.common.pay.service.internal.IWechatPayClient.NotifyContext;
import org.dromara.gz.common.pay.service.internal.MockWechatPayClient;
import org.dromara.gz.common.pay.service.internal.PayOrderNoGenerator;
import org.dromara.gz.common.pay.service.spi.PayCallbackDispatcher;
import org.dromara.gz.ord.domain.entity.GzOrdOrder;
import org.dromara.gz.ord.enums.LogisticsStatusEnum;
import org.dromara.gz.ord.enums.OrdBusinessStatusEnum;
import org.dromara.gz.ord.mapper.GzOrdOrderMapper;
import org.dromara.gz.ord.service.callback.PreorderPayCallbackHandler;
import org.dromara.gz.ord.service.impl.GzOrdOrderServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * GZ-ORD-104 AC8 — 预购 mock 全链路自测（跨模块：PAY-101 回调 → SPI 路由 → gz-ord handler → 订单 paid）。
 *
 * <p>用<b>真实</b> {@link MockWechatPayClient}（mock V3 解析）+ <b>真实</b> {@link PayCallbackDispatcher}
 * + <b>真实下沉的</b> {@link PreorderPayCallbackHandler}（gz-ord）+ <b>真实</b> {@link GzOrdOrderServiceImpl}，
 * 仅 mapper 用 in-memory 假实现模拟 DB（gz_pay_transaction + gz_ord_order 的 UNIQUE / 乐观锁 / 状态守卫）。</p>
 *
 * <pre>
 *   createBusinessOrder(preorder, business_order_no=PREORD-...) → mock 调起（mock_prepay_）
 *     → mock V3 回调 body（AES-GCM 解析路径）
 *     → handlePaymentNotify 乐观锁 pending→paid
 *     → PayCallbackDispatcher 按 business_type=preorder 路由到真 PreorderPayCallbackHandler.onPaid（gz-ord）
 *     → GzOrdOrderService.onPaid 行锁定位 gz_ord_order（created）→ markPaid → business_status=paid
 *   终态查：gz_ord_order.business_status='paid' + logistics_status='in_japan'
 *           + gz_pay_transaction.business_type='preorder' + transaction_id 回填
 *   重复回调 → 双层幂等（PAY 侧 duplicated + ord 侧 markPaid affected=0）
 * </pre>
 *
 * @author kevin-coder (sensenran-guzi · GZ-ORD-104)
 */
@Tag("dev")
@DisplayName("GZ-ORD-104 AC8 预购 mock 全链路（PAY→SPI→gz-ord→paid）")
@ExtendWith(MockitoExtension.class)
class PreorderFullChainMockTest {

    @Mock
    private GzPayTransactionMapper payTxMapper;
    @Mock
    private GzPayCallbackLogMapper callbackLogMapper;
    @Mock
    private GzPayRefundMapper refundMapper;
    @Mock
    private GzOrdOrderMapper ordOrderMapper;
    @Mock
    private org.dromara.gz.ord.mapper.GzOrdProductMapper ordProductMapper;

    private MockWechatPayClient mockClient;
    private GzPayTransactionServiceImpl payService;
    private GzOrdOrderServiceImpl ordOrderService;

    /** in-memory：gz_pay_transaction（out_trade_no → 行） */
    private final Map<String, GzPayTransaction> payDb = new HashMap<>();
    /** in-memory：gz_ord_order（order_no → 行） */
    private final Map<String, GzOrdOrder> orderDb = new HashMap<>();
    private final List<GzPayCallbackLog> callbackLogs = new ArrayList<>();
    private long payIdSeq = 5000L;

    @BeforeEach
    void setUp() {
        WechatPayProperties props = new WechatPayProperties();
        props.setClientMode("mock");
        mockClient = new MockWechatPayClient(props);

        // gz-ord 订单服务（onPaid 用，其它 collaborator 下单链路本测试不触发可传 null 安全引用；
        // ObjectMapper 仅 submit 序列化用，onPaid 路径不触及）
        ordOrderService = new GzOrdOrderServiceImpl(ordOrderMapper, ordProductMapper, null, null,
            null, null, null, null, null, new com.fasterxml.jackson.databind.ObjectMapper());

        // SPI：真实 dispatcher + 真实下沉的 gz-ord PreorderPayCallbackHandler（构造注入 ord 服务）
        PreorderPayCallbackHandler preorderHandler = new PreorderPayCallbackHandler(ordOrderService);
        PayCallbackDispatcher dispatcher = new PayCallbackDispatcher(List.of(preorderHandler));
        dispatcher.validate();

        PayOrderNoGenerator generator = new PayOrderNoGenerator(payTxMapper, refundMapper);
        payService = new GzPayTransactionServiceImpl(
            payTxMapper, callbackLogMapper, generator, mockClient, props, dispatcher);

        wirePayMappers();
        wireOrderMappers();
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

    private void wireOrderMappers() {
        lenient().when(ordOrderMapper.selectByOrderNoForUpdate(anyString()))
            .thenAnswer(inv -> orderDb.get((String) inv.getArgument(0)));
        lenient().when(ordOrderMapper.markPaid(any(), anyString(), any())).thenAnswer(inv -> {
            GzOrdOrder o = orderById(inv.getArgument(0));
            if (o != null && OrdBusinessStatusEnum.CREATED.getCode().equals(o.getBusinessStatus())) {
                o.setBusinessStatus(OrdBusinessStatusEnum.PAID.getCode());
                o.setPayTransactionId(inv.getArgument(1));
                o.setPaidTime(inv.getArgument(2));
                return 1;
            }
            return 0;
        });
        lenient().when(ordProductMapper.increaseSalesCount(any(), org.mockito.ArgumentMatchers.anyLong()))
            .thenReturn(1);
    }

    private GzPayTransaction payById(Long id) {
        return payDb.values().stream().filter(t -> id.equals(t.getId())).findFirst().orElse(null);
    }

    private GzOrdOrder orderById(Long id) {
        return orderDb.values().stream().filter(o -> id.equals(o.getId())).findFirst().orElse(null);
    }

    /** 预置一条 created 预购订单（模拟下单事务已落库 gz_ord_order） */
    private GzOrdOrder seedCreatedOrder(String orderNo) {
        GzOrdOrder o = new GzOrdOrder();
        o.setId(8001L);
        o.setOrderNo(orderNo);
        o.setUserId(2001L);
        o.setProductId(12L);
        o.setSkuId(100L);
        o.setQty(2);
        o.setTotalAmountCent(25800L);
        o.setBusinessStatus(OrdBusinessStatusEnum.CREATED.getCode());
        o.setLogisticsStatus(LogisticsStatusEnum.IN_JAPAN.getCode());
        o.setVersion(0);
        orderDb.put(orderNo, o);
        return o;
    }

    @Test
    @DisplayName("preorder 全链路：建单→mock V3 回调→SPI 路由 preorder(gz-ord)→订单 paid + in_japan；重复回调双层幂等")
    void preorderFullChain_mock() {
        // ★ business_order_no = order_no（gz-ord 下单事务生成的 PREORD-…），支付回调 SPI 据此定位订单
        String orderNo = "PREORD-20260603-000001";
        seedCreatedOrder(orderNo);

        // ① PAY 建单（business_type=preorder，businessOrderNo=orderNo）
        CreateOrderBo bo = CreateOrderBo.builder()
            .businessType(PayBusinessType.PREORDER)
            .businessOrderNo(orderNo)
            .amountCent(25800L)
            .openid("openid_buyer_001")
            .userId(2001L)
            .description("谷子宇宙预购 - CHIIKAWA 限定盲盒")
            .build();
        MpPayParamsVO params = payService.createBusinessOrder(bo);
        String outTradeNo = params.getOutTradeNo();
        System.out.println("[AC8] 建单 out_trade_no=" + outTradeNo + " business_order_no=" + orderNo
            + " 订单初态=" + orderDb.get(orderNo).getBusinessStatus());

        GzPayTransaction afterCreate = payDb.get(outTradeNo);
        assertEquals(PayStatus.PENDING, afterCreate.getStatus());
        assertEquals("preorder", afterCreate.getBusinessType());
        assertEquals(orderNo, afterCreate.getBusinessOrderNo());
        // 订单仍 created（支付前）
        assertEquals(OrdBusinessStatusEnum.CREATED.getCode(), orderDb.get(orderNo).getBusinessStatus());

        // ② mock V3 回调（AES-GCM 解析路径）
        String mockTxnId = "mock_wx_txn_" + outTradeNo;
        String body = mockClient.buildMockCallbackBody(outTradeNo, mockTxnId, 25800L);
        NotifyContext ctx = new NotifyContext("0", "mock_nonce", "mock_sig", "mock_serial", body);
        boolean ok = payService.handlePaymentNotify(ctx);
        assertTrue(ok);

        // ③ 终态查：gz_pay_transaction paid + transaction_id；gz_ord_order paid + in_japan + 回填 transaction_id
        GzPayTransaction afterPaid = payDb.get(outTradeNo);
        assertEquals(PayStatus.PAID, afterPaid.getStatus());
        assertEquals(mockTxnId, afterPaid.getTransactionId());
        assertEquals("preorder", afterPaid.getBusinessType());

        GzOrdOrder paidOrder = orderDb.get(orderNo);
        System.out.println("[AC8] 回调后 gz_ord_order business_status=" + paidOrder.getBusinessStatus()
            + " logistics_status=" + paidOrder.getLogisticsStatus()
            + " pay_transaction_id=" + paidOrder.getPayTransactionId());
        assertEquals(OrdBusinessStatusEnum.PAID.getCode(), paidOrder.getBusinessStatus(), "订单 → paid");
        assertEquals(LogisticsStatusEnum.IN_JAPAN.getCode(), paidOrder.getLogisticsStatus(), "物流默认 in_japan");
        assertEquals(mockTxnId, paidOrder.getPayTransactionId(), "pay_transaction_id 回填微信交易号");
        assertNotNull(paidOrder.getPaidTime(), "paid_time 写入");
        // SPI 路由命中 → 累加销量 1 次
        verify(ordProductMapper, times(1)).increaseSalesCount(12L, 2L);

        // ④ 重复回调 → 双层幂等：PAY 侧 duplicated（不二次 markPaid）+ ord 侧不二次累加销量
        boolean ok2 = payService.handlePaymentNotify(ctx);
        assertTrue(ok2, "重复回调幂等返回成功");
        assertEquals(PayStatus.PAID, payDb.get(outTradeNo).getStatus());
        assertEquals(OrdBusinessStatusEnum.PAID.getCode(), orderDb.get(orderNo).getBusinessStatus());
        // 销量仍只累加 1 次（PAY 侧已 paid → 提前 duplicated，根本不再 dispatch SPI）
        verify(ordProductMapper, times(1)).increaseSalesCount(12L, 2L);
        System.out.println("[AC8] 重复回调幂等 OK，销量仅累加 1 次，callback_logs=" + callbackLogs.size());
    }
}
