package org.dromara.gz.common.pay.service;

import org.dromara.common.core.exception.ServiceException;
import org.dromara.gz.common.pay.config.WechatPayProperties;
import org.dromara.gz.common.pay.domain.bo.RefundApplyBo;
import org.dromara.gz.common.pay.domain.entity.GzPayCallbackLog;
import org.dromara.gz.common.pay.domain.entity.GzPayRefund;
import org.dromara.gz.common.pay.domain.entity.GzPayTransaction;
import org.dromara.gz.common.pay.domain.vo.GzPayRefundVO;
import org.dromara.gz.common.pay.enums.PayBusinessType;
import org.dromara.gz.common.pay.enums.PayStatus;
import org.dromara.gz.common.pay.mapper.GzPayCallbackLogMapper;
import org.dromara.gz.common.pay.mapper.GzPayRefundMapper;
import org.dromara.gz.common.pay.mapper.GzPayTransactionMapper;
import org.dromara.gz.common.pay.service.impl.PayRefundServiceImpl;
import org.dromara.gz.common.pay.service.internal.IWechatPayClient.NotifyContext;
import org.dromara.gz.common.pay.service.internal.MockWechatPayClient;
import org.dromara.gz.common.pay.service.internal.PayOrderNoGenerator;
import org.dromara.gz.common.pay.service.internal.PayRefundTxService;
import org.dromara.gz.common.pay.service.spi.IRefundCallbackHandler;
import org.dromara.gz.common.pay.service.spi.RefundCallbackDispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;

/**
 * GZ-PAY-103 AC 9 — 退款服务 mock 全链路自测（5 个测试方法）。
 *
 * <p>用<b>真实</b> {@link MockWechatPayClient}（mock V3 退款 / 退款回调解析路径）+ <b>真实</b>
 * {@link RefundCallbackDispatcher} + <b>测试内联 preorder {@link IRefundCallbackHandler}</b>（真实退款
 * handler 已下沉 ruoyi-gz-ord 模块，gz-common 单测只验退款 SPI 按 business_type=preorder 路由命中，
 * 用内联匿名 handler 即可，不依赖 gz-ord）+ <b>真实</b> {@link PayRefundTxService}，仅 mapper 用 in-memory
 * 假实现模拟 DB 的 UNIQUE / 状态机守卫，跑通：</p>
 *
 * <pre>
 *   ① happy path：paid 单 → apply → mock 受理成功 → 模拟 V3 退款回调 SUCCESS
 *        → gz_pay_refund.status=refunded + gz_pay_transaction.status=refunded + 退款 SPI 命中 preorder
 *   ② 非 paid 不允许退（抛 ServiceException）
 *   ③ 重复退拦截（同 transaction 已 refunding → 抛 ServiceException）
 *   ④ 回调幂等（已 refunded → 二次回调不重复变更）
 *   ⑤ 受理失败回滚（mock 受理失败 → refund=failed + transaction 回 paid + 抛异常）
 * </pre>
 *
 * <p>不依赖 Spring 上下文 / 真实 DB，CI 稳定（与 GzPayBusinessFullChainMockTest 同款思路）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-PAY-103)
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class PayRefundServiceImplTest {

    @Mock
    private GzPayRefundMapper refundMapper;

    @Mock
    private GzPayTransactionMapper transactionMapper;

    @Mock
    private GzPayCallbackLogMapper callbackLogMapper;

    private MockWechatPayClient mockClient;
    private RefundCallbackDispatcher refundDispatcher;
    /** 测试内联 preorder 退款 handler（真实 handler 已下沉 gz-ord，此处仅验退款 SPI 路由命中） */
    private IRefundCallbackHandler preorderRefundHandler;
    private PayRefundTxService refundTxService;
    private PayRefundServiceImpl service;

    /** in-memory 退款表（refund_no → 退款行），模拟 UNIQUE + 状态机守卫 */
    private final Map<String, GzPayRefund> refundDb = new HashMap<>();
    /** in-memory 交易表（id → 交易行） */
    private final Map<Long, GzPayTransaction> txnDb = new HashMap<>();
    /** callback_log 内存表（断言用） */
    private final List<GzPayCallbackLog> callbackLogs = new ArrayList<>();
    private long refundIdSeq = 9000L;

    @BeforeEach
    void setUp() {
        WechatPayProperties props = new WechatPayProperties();
        props.setClientMode("mock");
        props.setRefundNotifyUrl("/api/pay/v3/refund-notify");
        mockClient = new MockWechatPayClient(props);

        // 测试内联 preorder 退款 handler（验 business_type=preorder 退款路由命中即可，
        // 真实回滚逻辑已下沉 ruoyi-gz-ord PreorderRefundCallbackHandler，gz-common 不依赖 gz-ord）
        preorderRefundHandler = new IRefundCallbackHandler() {
            @Override
            public String supportedBusinessType() {
                return PayBusinessType.PREORDER;
            }

            @Override
            public void onRefunded(GzPayRefund refund, GzPayTransaction txn) {
                // 内联占位：仅验退款 SPI 路由命中 preorder，不做业务回滚
            }
        };
        refundDispatcher = new RefundCallbackDispatcher(List.of(preorderRefundHandler));
        refundDispatcher.validate();

        PayOrderNoGenerator generator = new PayOrderNoGenerator(transactionMapper, refundMapper, PayGeneratorTestSupport.inMemoryRedisson());
        refundTxService = new PayRefundTxService(refundMapper, transactionMapper, generator);
        service = new PayRefundServiceImpl(
            refundMapper, transactionMapper, callbackLogMapper, refundTxService, mockClient, props, refundDispatcher);

        wireInMemoryMappers();
    }

    private void wireInMemoryMappers() {
        // refund_no 序号：内存恒 0 → 序号从 000001 起
        lenient().when(refundMapper.selectMaxDailySeq(anyString())).thenReturn(0L);

        // refund insert：分配 id + 落库
        lenient().when(refundMapper.insert(any(GzPayRefund.class))).thenAnswer(inv -> {
            GzPayRefund r = inv.getArgument(0);
            r.setId(refundIdSeq++);
            r.setDelFlag("0");
            refundDb.put(r.getRefundNo(), r);
            return 1;
        });

        // refund selectById
        lenient().when(refundMapper.selectById(any())).thenAnswer(inv -> {
            Long id = inv.getArgument(0);
            return refundDb.values().stream().filter(r -> id.equals(r.getId())).findFirst().orElse(null);
        });

        // refund selectByRefundNoForUpdate
        lenient().when(refundMapper.selectByRefundNoForUpdate(anyString()))
            .thenAnswer(inv -> refundDb.get((String) inv.getArgument(0)));

        // countActiveByTransactionId：同 transaction_id refunding/refunded 计数
        lenient().when(refundMapper.countActiveByTransactionId(anyString())).thenAnswer(inv -> {
            String txnId = inv.getArgument(0);
            return refundDb.values().stream()
                .filter(r -> txnId.equals(r.getTransactionId()))
                .filter(r -> PayStatus.REFUNDING.equals(r.getStatus()) || PayStatus.REFUNDED.equals(r.getStatus()))
                .count();
        });

        // refund markRefunded：refunding → refunded 守卫
        lenient().when(refundMapper.markRefunded(any(), anyString(), any())).thenAnswer(inv -> {
            Long id = inv.getArgument(0);
            GzPayRefund r = findRefundById(id);
            if (r != null && PayStatus.REFUNDING.equals(r.getStatus())) {
                r.setStatus(PayStatus.REFUNDED);
                r.setWechatRefundId(inv.getArgument(1));
                r.setRefundedTime(inv.getArgument(2));
                return 1;
            }
            return 0;
        });

        // refund markFailed：refunding → failed 守卫
        lenient().when(refundMapper.markFailed(any(), any())).thenAnswer(inv -> {
            Long id = inv.getArgument(0);
            String wxRefundId = inv.getArgument(1);
            GzPayRefund r = findRefundById(id);
            if (r != null && PayStatus.REFUNDING.equals(r.getStatus())) {
                r.setStatus(PayStatus.FAILED);
                if (wxRefundId != null) {
                    r.setWechatRefundId(wxRefundId);
                }
                return 1;
            }
            return 0;
        });

        // transaction selectByIdForUpdate / selectByOutTradeNo
        lenient().when(transactionMapper.selectByIdForUpdate(any()))
            .thenAnswer(inv -> txnDb.get((Long) inv.getArgument(0)));
        lenient().when(transactionMapper.selectByOutTradeNo(anyString())).thenAnswer(inv -> {
            String outTradeNo = inv.getArgument(0);
            return txnDb.values().stream().filter(t -> outTradeNo.equals(t.getOutTradeNo())).findFirst().orElse(null);
        });

        // transaction markRefunding / markRefunded / rollback 守卫
        lenient().when(transactionMapper.markRefunding(any())).thenAnswer(inv -> {
            GzPayTransaction t = txnDb.get((Long) inv.getArgument(0));
            if (t != null && PayStatus.PAID.equals(t.getStatus())) {
                t.setStatus(PayStatus.REFUNDING);
                return 1;
            }
            return 0;
        });
        lenient().when(transactionMapper.markRefunded(any())).thenAnswer(inv -> {
            GzPayTransaction t = txnDb.get((Long) inv.getArgument(0));
            if (t != null && PayStatus.REFUNDING.equals(t.getStatus())) {
                t.setStatus(PayStatus.REFUNDED);
                return 1;
            }
            return 0;
        });
        lenient().when(transactionMapper.rollbackRefundingToPaid(any())).thenAnswer(inv -> {
            GzPayTransaction t = txnDb.get((Long) inv.getArgument(0));
            if (t != null && PayStatus.REFUNDING.equals(t.getStatus())) {
                t.setStatus(PayStatus.PAID);
                return 1;
            }
            return 0;
        });

        // callback_log insert
        lenient().when(callbackLogMapper.insert(any(GzPayCallbackLog.class))).thenAnswer(inv -> {
            callbackLogs.add(inv.getArgument(0));
            return 1;
        });
    }

    private GzPayRefund findRefundById(Long id) {
        return refundDb.values().stream().filter(r -> id.equals(r.getId())).findFirst().orElse(null);
    }

    /** 造一笔 paid 的 preorder 支付交易行 */
    private GzPayTransaction givenPaidTransaction(long id, String txnId) {
        GzPayTransaction txn = GzPayTransaction.builder()
            .id(id)
            .outTradeNo("PREORD-20260606-000001")
            .businessType(PayBusinessType.PREORDER)
            .businessOrderNo("PREORD-ORDER-001")
            .amountCent(9900L)
            .status(PayStatus.PAID)
            .transactionId(txnId)
            .version(2)
            .build();
        txnDb.put(id, txn);
        return txn;
    }

    private RefundApplyBo applyBo(long txRowId) {
        RefundApplyBo bo = new RefundApplyBo();
        bo.setTransactionId(txRowId);
        bo.setReason("买家协商一致退款");
        return bo;
    }

    @Test
    @DisplayName("AC9 ①：paid 单 → apply → mock 受理成功 → 模拟 V3 退款回调 SUCCESS → refunded + transaction refunded + SPI 命中")
    void happyPath_applyThenRefundedCallback() {
        String txnId = "mock_wx_txn_001";
        givenPaidTransaction(100L, txnId);

        // apply：全额退款（系统取 amount_cent=9900）
        GzPayRefundVO vo = service.apply(applyBo(100L), "gz_owner");
        assertEquals(PayStatus.REFUNDING, vo.getStatus());
        assertEquals(9900L, vo.getRefundAmountCent(), "全额退款金额 = 原单 amount_cent");
        assertTrue(vo.getRefundNo().startsWith("RF-"), "refund_no 前缀 RF-：" + vo.getRefundNo());
        assertEquals("gz_owner", vo.getTriggeredBy());
        assertEquals(PayStatus.REFUNDING, txnDb.get(100L).getStatus(), "transaction paid → refunding");

        // 模拟 V3 退款回调 SUCCESS
        String body = mockClient.buildMockRefundCallbackBody(
            "PREORD-20260606-000001", vo.getRefundNo(), "mock_refund_id_" + vo.getRefundNo(), "SUCCESS");
        NotifyContext ctx = new NotifyContext("0", "n", "s", "ser", body);
        boolean ok = service.handleRefundNotify(ctx);

        assertTrue(ok);
        GzPayRefund refundedRow = refundDb.get(vo.getRefundNo());
        assertEquals(PayStatus.REFUNDED, refundedRow.getStatus());
        assertEquals("mock_refund_id_" + vo.getRefundNo(), refundedRow.getWechatRefundId());
        assertNotNull(refundedRow.getRefundedTime());
        assertEquals(PayStatus.REFUNDED, txnDb.get(100L).getStatus(), "transaction refunding → refunded");
        // callback_log：received + processed（退款 SPI 命中 preorder handler，见日志）
        assertEquals(2, callbackLogs.size());
        assertEquals("received", callbackLogs.get(0).getProcessStatus());
        assertEquals("processed", callbackLogs.get(1).getProcessStatus());
        assertEquals("refund", callbackLogs.get(0).getCallbackType(), "callback_type 独立 refund key 空间");
    }

    @Test
    @DisplayName("AC9 ②：非 paid 订单不允许退款（pending → 抛 ServiceException）")
    void nonPaidOrder_rejected() {
        GzPayTransaction txn = GzPayTransaction.builder()
            .id(101L).outTradeNo("PREORD-20260606-000002").businessType(PayBusinessType.PREORDER)
            .amountCent(5000L).status(PayStatus.PENDING).transactionId(null).version(1).build();
        txnDb.put(101L, txn);

        ServiceException ex = assertThrows(ServiceException.class, () -> service.apply(applyBo(101L), "gz_owner"));
        assertTrue(ex.getMessage().contains("仅已支付"), ex.getMessage());
        assertEquals(PayStatus.PENDING, txnDb.get(101L).getStatus(), "状态不变");
        assertTrue(refundDb.isEmpty(), "不产生退款单");
    }

    @Test
    @DisplayName("AC9 ③：重复退款拦截（同 transaction 已有 refunding 退款单 → 抛 ServiceException）")
    void duplicateRefund_rejected() {
        String txnId = "mock_wx_txn_003";
        givenPaidTransaction(102L, txnId);
        // 先成功 apply 一笔（transaction → refunding，存在 refunding 退款单）
        service.apply(applyBo(102L), "gz_owner");
        // 人为把 transaction 复位 paid（模拟极端并发：transaction 已回 paid 但退款单仍 refunding），验防重复退第二道防线
        txnDb.get(102L).setStatus(PayStatus.PAID);

        ServiceException ex = assertThrows(ServiceException.class, () -> service.apply(applyBo(102L), "gz_owner"));
        assertTrue(ex.getMessage().contains("已有进行中或已完成的退款"), ex.getMessage());
        assertEquals(1, refundDb.size(), "不产生第二条退款单");
    }

    @Test
    @DisplayName("AC9 ④：回调幂等（同退款单 SUCCESS 二次回调 → duplicated，不重复变更）")
    void refundCallback_idempotent() {
        givenPaidTransaction(103L, "mock_wx_txn_004");
        GzPayRefundVO vo = service.apply(applyBo(103L), "gz_owner");

        String body = mockClient.buildMockRefundCallbackBody(
            "PREORD-20260606-000001", vo.getRefundNo(), "mock_refund_id_x", "SUCCESS");
        NotifyContext ctx = new NotifyContext("0", "n", "s", "ser", body);

        assertTrue(service.handleRefundNotify(ctx));
        assertEquals(PayStatus.REFUNDED, refundDb.get(vo.getRefundNo()).getStatus());
        int afterFirst = callbackLogs.size();

        // 第二次回调 → 已终态 → duplicated，不再变更
        assertTrue(service.handleRefundNotify(ctx), "重复回调幂等返回成功");
        assertEquals(PayStatus.REFUNDED, refundDb.get(vo.getRefundNo()).getStatus());
        GzPayCallbackLog last = callbackLogs.get(callbackLogs.size() - 1);
        assertEquals("duplicated", last.getProcessStatus());
        assertEquals(afterFirst + 2, callbackLogs.size(), "第二次回调记 received + duplicated 两条审计");
    }

    @Test
    @DisplayName("AC9 ⑤：受理失败回滚（mock 受理失败 → refund=failed + transaction 回 paid + 抛异常）")
    void acceptFail_rollback() {
        givenPaidTransaction(104L, "mock_wx_txn_005");
        mockClient.setRefundAcceptFail(true);  // 注入受理失败

        ServiceException ex = assertThrows(ServiceException.class, () -> service.apply(applyBo(104L), "gz_owner"));
        assertTrue(ex.getMessage().contains("退款受理失败"), ex.getMessage());
        // 退款单 failed + transaction 回滚 paid（doc/10 §6.E4）
        assertEquals(1, refundDb.size());
        GzPayRefund r = refundDb.values().iterator().next();
        assertEquals(PayStatus.FAILED, r.getStatus());
        assertNull(r.getWechatRefundId(), "受理失败 wechat_refund_id 仍为 null");
        assertEquals(PayStatus.PAID, txnDb.get(104L).getStatus(), "transaction refunding → paid 回滚");
    }
}
