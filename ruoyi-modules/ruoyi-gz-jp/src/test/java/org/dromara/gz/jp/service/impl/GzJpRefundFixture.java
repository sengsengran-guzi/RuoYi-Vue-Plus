package org.dromara.gz.jp.service.impl;

import org.dromara.gz.common.pay.config.WechatPayProperties;
import org.dromara.gz.common.pay.domain.entity.GzPayTransaction;
import org.dromara.gz.common.pay.mapper.GzPayCallbackLogMapper;
import org.dromara.gz.common.pay.mapper.GzPayTransactionMapper;
import org.dromara.gz.common.pay.service.IGzPayShippingService;
import org.dromara.gz.common.pay.service.internal.MockWechatPayClient;
import org.dromara.gz.common.service.IGzUserService;
import org.dromara.gz.jp.config.GzJpPayProperties;
import org.dromara.gz.jp.domain.entity.GzJpOrder;
import org.dromara.gz.jp.domain.entity.GzJpOrderItem;
import org.dromara.gz.jp.domain.entity.GzJpRefund;
import org.dromara.gz.jp.domain.enums.GzJpFulfillStatus;
import org.dromara.gz.jp.domain.enums.GzJpOrderStatus;
import org.dromara.gz.jp.domain.enums.GzJpRefundStatus;
import org.dromara.gz.jp.mapper.GzJpOrderItemMapper;
import org.dromara.gz.jp.mapper.GzJpOrderMapper;
import org.dromara.gz.jp.mapper.GzJpRefundMapper;
import org.dromara.gz.jp.service.internal.GzJpRefundNoGenerator;
import org.dromara.gz.jp.service.internal.GzJpRefundTxService;
import org.mockito.Mockito;
import org.springframework.dao.DuplicateKeyException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;

/**
 * 行级退款的<b>内存版数据层</b>（GZ-JP-107 单测夹具）。
 *
 * <p><b>为什么不是纯 mock 桩</b>：本卡的正确性几乎全部落在 SQL 的守卫上 ——
 * {@code UNIQUE(tenant_id, order_item_id)}（一行至多一条退款单）、
 * {@code WHERE status='refunding'}（回调幂等）、
 * {@code WHERE business_status IN ('paid','partial_refunded')}（订单 rollup 不降级）。
 * 用 {@code when(...).thenReturn(1)} 把 mapper 桩掉，等于把要测的东西删掉了。
 * 这里<b>逐字照搬</b>那几道守卫（含唯一键 → 抛 {@link DuplicateKeyException}），
 * 让单测真的在验判定链路（同 106 的 {@code GzJpFulfillFixture} 做法）。</p>
 *
 * <p><b>微信通道用真的 {@link MockWechatPayClient}</b>（不是 Mockito 桩）：
 * 它自带 {@code setRefundAcceptFail} 开关，能真实驱动「受理失败 → refund_failed」那条分支，
 * 且 {@code buildMockRefundCallbackBody} 能造出与真实回调同构的 body 喂给回调链路。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-JP-107)
 */
class GzJpRefundFixture {

    // ============ 内存"表" ============

    final Map<Long, GzJpOrderItem> items = new LinkedHashMap<>();
    final Map<Long, GzJpOrder> orders = new LinkedHashMap<>();
    final Map<Long, GzJpRefund> refunds = new LinkedHashMap<>();
    final Map<Long, GzPayTransaction> txns = new LinkedHashMap<>();

    private final AtomicLong refundIdSeq = new AtomicLong(1000L);
    private final AtomicLong refundNoSeq = new AtomicLong(0L);

    /** 每次 selectByIdsForUpdate 收到的 id 列表（原样记录，用来断言「去重 + 升序」防死锁） */
    final List<List<Long>> lockCalls = new ArrayList<>();

    // ============ 被测对象及其依赖 ============

    final GzJpOrderItemMapper itemMapper = Mockito.mock(GzJpOrderItemMapper.class);
    final GzJpOrderMapper orderMapper = Mockito.mock(GzJpOrderMapper.class);
    final GzJpRefundMapper refundMapper = Mockito.mock(GzJpRefundMapper.class);
    final GzPayTransactionMapper txnMapper = Mockito.mock(GzPayTransactionMapper.class);
    final GzPayCallbackLogMapper callbackLogMapper = Mockito.mock(GzPayCallbackLogMapper.class);
    final IGzUserService userService = Mockito.mock(IGzUserService.class);
    final GzJpRefundNoGenerator noGenerator = Mockito.mock(GzJpRefundNoGenerator.class);
    /** 发货上报（标购买失败可能让整单就此发完 → 收口重报，GZ-JP-301） */
    final IGzPayShippingService shippingService = Mockito.mock(IGzPayShippingService.class);

    final WechatPayProperties payProperties = new WechatPayProperties();
    final GzJpPayProperties jpPayProperties = new GzJpPayProperties();
    final MockWechatPayClient payClient = new MockWechatPayClient(new WechatPayProperties());

    final GzJpRefundTxService txService;
    final GzJpRefundServiceImpl service;

    GzJpRefundFixture() {
        Mockito.when(noGenerator.generate())
            .thenAnswer(inv -> String.format("JPRF-20260807-%06d", refundNoSeq.incrementAndGet()));
        wireItemMapper();
        wireOrderMapper();
        wireRefundMapper();
        wireTxnMapper();
        txService = new GzJpRefundTxService(itemMapper, orderMapper, refundMapper, txnMapper, noGenerator);
        service = new GzJpRefundServiceImpl(txService, refundMapper, orderMapper, itemMapper,
            payClient, payProperties, jpPayProperties, callbackLogMapper, userService,
            txnMapper, shippingService);
    }

    // ============================================================
    //  数据准备
    // ============================================================

    /** 建一张已支付订单 + 对应支付流水（这是行级退款的前提：没有流水就没法向微信证明退的是哪一单） */
    GzJpRefundFixture paidOrder(long orderId, long userId, long totalCent) {
        return order(orderId, userId, totalCent, GzJpOrderStatus.PAID.getCode(), true);
    }

    /** 建一张未支付订单（★ 它的行 fulfill_status 同样是 purchasing —— 本域最容易漏的坑） */
    GzJpRefundFixture unpaidOrder(long orderId, long userId, long totalCent) {
        return order(orderId, userId, totalCent, GzJpOrderStatus.CREATED.getCode(), false);
    }

    GzJpRefundFixture order(long orderId, long userId, long totalCent, String businessStatus, boolean withTxn) {
        GzJpOrder o = new GzJpOrder();
        o.setId(orderId);
        o.setUserId(userId);
        o.setOrderNo("JPO-20260807-" + String.format("%06d", orderId));
        o.setTotalAmountCent(totalCent);
        o.setBusinessStatus(businessStatus);
        o.setVersion(1);
        o.setDelFlag("0");
        if (withTxn) {
            long txnId = 5000L + orderId;
            GzPayTransaction t = new GzPayTransaction();
            t.setId(txnId);
            t.setOutTradeNo("JPO-20260807-" + String.format("%06d", orderId + 1));
            t.setBusinessType("jp");
            // 已支付单必然有微信支付单号（发货上报按它定位；缺了会被当成「无微信流水」跳过）
            t.setTransactionId("4200MOCK" + orderId);
            t.setBusinessOrderNo(o.getOrderNo());
            t.setAmountCent(totalCent);
            t.setStatus("paid");
            t.setDelFlag("0");
            txns.put(txnId, t);
            o.setPayTransactionId(txnId);
        }
        orders.put(orderId, o);
        return this;
    }

    /** 建一行商品行（挂在已建的订单上） */
    GzJpRefundFixture item(long itemId, long orderId, long amountCent, String fulfillStatus) {
        GzJpOrder o = orders.get(orderId);
        if (o == null) {
            throw new IllegalStateException("先建订单 " + orderId);
        }
        GzJpOrderItem it = new GzJpOrderItem();
        it.setId(itemId);
        it.setOrderId(orderId);
        it.setUserId(o.getUserId());
        it.setProductId(900L + itemId);
        it.setQty(1);
        it.setUnitPriceCent(amountCent);
        it.setAmountCent(amountCent);
        it.setFulfillStatus(fulfillStatus);
        it.setVersion(1);
        it.setDelFlag("0");
        items.put(itemId, it);
        return this;
    }

    // ============ 便捷读取 ============

    GzJpOrderItem row(long itemId) {
        return items.get(itemId);
    }

    String orderStatus(long orderId) {
        return orders.get(orderId).getBusinessStatus();
    }

    GzJpRefund refundOf(long itemId) {
        return refunds.values().stream()
            .filter(r -> itemId == r.getOrderItemId() && "0".equals(r.getDelFlag()))
            .findFirst().orElse(null);
    }

    /** 造一个与真实回调同构的 body（走 MockWechatPayClient 的解析路径，不是自己拼 JSON） */
    String callbackBody(GzJpRefund refund, String refundStatus) {
        return payClient.buildMockRefundCallbackBody(refund.getOutTradeNo(), refund.getRefundNo(),
            "mock_refund_id_" + refund.getRefundNo(), refundStatus);
    }

    // ============================================================
    //  内存数据层（★ 逐字照搬 SQL 的守卫）
    // ============================================================

    private void wireItemMapper() {
        Mockito.when(itemMapper.selectByIdsForUpdate(any())).thenAnswer(inv -> {
            Collection<Long> ids = inv.getArgument(0);
            lockCalls.add(new ArrayList<>(ids));
            List<GzJpOrderItem> out = new ArrayList<>();
            for (Long id : ids) {
                GzJpOrderItem it = items.get(id);
                if (it != null && "0".equals(it.getDelFlag())) {
                    out.add(it);
                }
            }
            return out;
        });

        // SQL 守卫：del_flag='0' AND fulfill_status=#{expectFrom} AND EXISTS(订单付过款)
        Mockito.when(itemMapper.advanceGuarded(any(), any(), any(), any())).thenAnswer(inv -> {
            Collection<Long> ids = inv.getArgument(0);
            String expectFrom = inv.getArgument(1);
            String target = inv.getArgument(2);
            int affected = 0;
            for (Long id : ids) {
                GzJpOrderItem it = items.get(id);
                if (it == null || !"0".equals(it.getDelFlag()) || !expectFrom.equals(it.getFulfillStatus())) {
                    continue;
                }
                GzJpOrder o = orders.get(it.getOrderId());
                if (o == null || !"0".equals(o.getDelFlag()) || !GzJpOrderStatus.isPaidLike(o.getBusinessStatus())) {
                    continue;
                }
                it.setFulfillStatus(target);
                it.setVersion(it.getVersion() + 1);
                affected++;
            }
            return affected;
        });

        // 行上的退款结果投影（刻意无状态守卫，见 mapper javadoc）
        Mockito.when(itemMapper.writeRefundResult(any(), any(), any())).thenAnswer(inv -> {
            Long itemId = inv.getArgument(0);
            GzJpOrderItem it = items.get(itemId);
            if (it == null || !"0".equals(it.getDelFlag())) {
                return 0;
            }
            it.setRefundStatus(inv.getArgument(1));
            it.setRefundAmountCent(inv.getArgument(2));
            it.setVersion(it.getVersion() + 1);
            return 1;
        });

        // GZ-JP-301 发货收口用：本订单还有几行既没发货、也没购买失败（逐字照搬 SQL 的 NOT IN）
        Mockito.when(itemMapper.countUnfinishedByOrderId(anyLong())).thenAnswer(inv -> {
            Long orderId = inv.getArgument(0);
            int n = 0;
            for (GzJpOrderItem it : items.values()) {
                if (orderId.equals(it.getOrderId()) && "0".equals(it.getDelFlag())
                    && !GzJpFulfillStatus.DELIVERED.getCode().equals(it.getFulfillStatus())
                    && !GzJpFulfillStatus.PURCHASE_FAILED.getCode().equals(it.getFulfillStatus())) {
                    n++;
                }
            }
            return n;
        });
        Mockito.when(itemMapper.selectByIds(any())).thenAnswer(inv -> {
            Collection<?> ids = inv.getArgument(0);
            List<GzJpOrderItem> out = new ArrayList<>();
            for (Object id : ids) {
                GzJpOrderItem it = items.get((Long) id);
                if (it != null && "0".equals(it.getDelFlag())) {
                    out.add(it);
                }
            }
            return out;
        });

        Mockito.when(itemMapper.countByOrderId(anyLong())).thenAnswer(inv -> {
            Long orderId = inv.getArgument(0);
            return (int) items.values().stream()
                .filter(i -> orderId.equals(i.getOrderId()) && "0".equals(i.getDelFlag())).count();
        });

        // ★ 只数 refunded（钱真的回去了），不数 refunding / refund_failed
        Mockito.when(itemMapper.countRefundedByOrderId(anyLong())).thenAnswer(inv -> {
            Long orderId = inv.getArgument(0);
            return (int) items.values().stream()
                .filter(i -> orderId.equals(i.getOrderId()) && "0".equals(i.getDelFlag()))
                .filter(i -> GzJpRefundStatus.REFUNDED.getCode().equals(i.getRefundStatus())).count();
        });

        // 全额退款旁路：只碰 refund_status IS NULL 的行，不碰 fulfill_status
        Mockito.when(itemMapper.markAllRefundedForFullRefund(anyLong())).thenAnswer(inv -> {
            Long orderId = inv.getArgument(0);
            int affected = 0;
            for (GzJpOrderItem it : items.values()) {
                if (orderId.equals(it.getOrderId()) && "0".equals(it.getDelFlag()) && it.getRefundStatus() == null) {
                    it.setRefundStatus(GzJpRefundStatus.REFUNDED.getCode());
                    it.setRefundAmountCent(it.getAmountCent());
                    it.setVersion(it.getVersion() + 1);
                    affected++;
                }
            }
            return affected;
        });
    }

    private void wireOrderMapper() {
        Mockito.when(orderMapper.selectByIds(any())).thenAnswer(inv -> {
            Collection<?> ids = inv.getArgument(0);
            List<GzJpOrder> out = new ArrayList<>();
            for (Object id : ids) {
                GzJpOrder o = orders.get((Long) id);
                if (o != null) {
                    out.add(o);
                }
            }
            return out;
        });
        Mockito.when(orderMapper.selectById(any())).thenAnswer(inv -> orders.get((Long) inv.getArgument(0)));
        Mockito.when(orderMapper.selectByIdForUpdate(anyLong()))
            .thenAnswer(inv -> orders.get((Long) inv.getArgument(0)));
        Mockito.when(orderMapper.selectByOrderNoForUpdate(any())).thenAnswer(inv -> {
            String orderNo = inv.getArgument(0);
            return orders.values().stream()
                .filter(o -> orderNo.equals(o.getOrderNo()) && "0".equals(o.getDelFlag()))
                .findFirst().orElse(null);
        });

        // SQL 守卫：business_status IN ('paid','partial_refunded') AND business_status <> #{target}
        //   → refunded 是订单级终态，不会被后到的 rollup 降级回 partial_refunded
        Mockito.when(orderMapper.markRefundRollup(anyLong(), any())).thenAnswer(inv -> {
            GzJpOrder o = orders.get((Long) inv.getArgument(0));
            String target = inv.getArgument(1);
            if (o == null || !"0".equals(o.getDelFlag())) {
                return 0;
            }
            boolean fromOk = GzJpOrderStatus.PAID.getCode().equals(o.getBusinessStatus())
                || GzJpOrderStatus.PARTIAL_REFUNDED.getCode().equals(o.getBusinessStatus());
            if (!fromOk || target.equals(o.getBusinessStatus())) {
                return 0;
            }
            o.setBusinessStatus(target);
            o.setVersion(o.getVersion() + 1);
            return 1;
        });
    }

    private void wireRefundMapper() {
        // ★ INSERT 带 UNIQUE(tenant_id, order_item_id) —— 一行至多一条退款单，撞了抛 DuplicateKeyException
        Mockito.when(refundMapper.insert(any(GzJpRefund.class))).thenAnswer(inv -> {
            GzJpRefund r = inv.getArgument(0);
            boolean dupItem = refunds.values().stream()
                .anyMatch(e -> e.getOrderItemId().equals(r.getOrderItemId()) && "0".equals(e.getDelFlag()));
            if (dupItem) {
                throw new DuplicateKeyException("Duplicate entry for key 'uk_order_item'");
            }
            boolean dupNo = refunds.values().stream().anyMatch(e -> e.getRefundNo().equals(r.getRefundNo()));
            if (dupNo) {
                throw new DuplicateKeyException("Duplicate entry for key 'uk_refund_no'");
            }
            r.setId(refundIdSeq.incrementAndGet());
            refunds.put(r.getId(), r);
            return 1;
        });

        Mockito.when(refundMapper.selectById(any()))
            .thenAnswer(inv -> refunds.get((Long) inv.getArgument(0)));
        Mockito.when(refundMapper.selectByIdForUpdate(anyLong()))
            .thenAnswer(inv -> refunds.get((Long) inv.getArgument(0)));

        Mockito.when(refundMapper.selectByOrderItemIds(any())).thenAnswer(inv -> {
            Collection<Long> ids = inv.getArgument(0);
            List<GzJpRefund> out = new ArrayList<>();
            for (GzJpRefund r : refunds.values()) {
                if ("0".equals(r.getDelFlag()) && ids.contains(r.getOrderItemId())) {
                    out.add(r);
                }
            }
            return out;
        });

        Mockito.when(refundMapper.selectByRefundNoForUpdate(any())).thenAnswer(inv -> {
            String no = inv.getArgument(0);
            return refunds.values().stream()
                .filter(r -> no.equals(r.getRefundNo()) && "0".equals(r.getDelFlag()))
                .findFirst().orElse(null);
        });

        // status IN ('refunding','refunded') —— refunding 也算占用，否则并发第二笔会溜过超退校验
        Mockito.when(refundMapper.sumActiveAmountByOrderId(anyLong())).thenAnswer(inv -> {
            Long orderId = inv.getArgument(0);
            return refunds.values().stream()
                .filter(r -> orderId.equals(r.getOrderId()) && "0".equals(r.getDelFlag()))
                .filter(r -> GzJpRefundStatus.REFUNDING.getCode().equals(r.getStatus())
                    || GzJpRefundStatus.REFUNDED.getCode().equals(r.getStatus()))
                .mapToLong(GzJpRefund::getRefundAmountCent).sum();
        });

        Mockito.when(refundMapper.selectMaxDailySeq(any())).thenReturn(0L);

        // WHERE status='refunding' 守卫（幂等的落点）
        Mockito.when(refundMapper.markAccepted(anyLong(), any())).thenAnswer(inv -> {
            GzJpRefund r = guarded(inv.getArgument(0), GzJpRefundStatus.REFUNDING.getCode());
            if (r == null) {
                return 0;
            }
            r.setWechatRefundId(inv.getArgument(1));
            r.setAttemptCount(r.getAttemptCount() + 1);
            r.setVersion(r.getVersion() + 1);
            return 1;
        });

        Mockito.when(refundMapper.markFailed(anyLong(), any(), any())).thenAnswer(inv -> {
            GzJpRefund r = guarded(inv.getArgument(0), GzJpRefundStatus.REFUNDING.getCode());
            if (r == null) {
                return 0;
            }
            r.setStatus(GzJpRefundStatus.REFUND_FAILED.getCode());
            r.setFailReason(inv.getArgument(2));
            if (inv.getArgument(1) != null) {
                r.setWechatRefundId(inv.getArgument(1));
            }
            r.setVersion(r.getVersion() + 1);
            return 1;
        });

        Mockito.when(refundMapper.markRefunded(anyLong(), any(), any())).thenAnswer(inv -> {
            GzJpRefund r = guarded(inv.getArgument(0), GzJpRefundStatus.REFUNDING.getCode());
            if (r == null) {
                return 0;
            }
            r.setStatus(GzJpRefundStatus.REFUNDED.getCode());
            r.setRefundedTime(inv.getArgument(2));
            r.setFailReason(null);
            if (inv.getArgument(1) != null) {
                r.setWechatRefundId(inv.getArgument(1));
            }
            r.setVersion(r.getVersion() + 1);
            return 1;
        });

        Mockito.when(refundMapper.markRetrying(anyLong())).thenAnswer(inv -> {
            GzJpRefund r = guarded(inv.getArgument(0), GzJpRefundStatus.REFUND_FAILED.getCode());
            if (r == null) {
                return 0;
            }
            r.setStatus(GzJpRefundStatus.REFUNDING.getCode());
            r.setFailReason(null);
            r.setTriggeredTime(LocalDateTime.now());
            r.setVersion(r.getVersion() + 1);
            return 1;
        });
    }

    private GzJpRefund guarded(Long id, String expectStatus) {
        GzJpRefund r = refunds.get(id);
        if (r == null || !"0".equals(r.getDelFlag()) || !expectStatus.equals(r.getStatus())) {
            return null;
        }
        return r;
    }

    private void wireTxnMapper() {
        Mockito.when(txnMapper.selectById(any())).thenAnswer(inv -> txns.get((Long) inv.getArgument(0)));
    }

    // ============ 常量别名（测试里少写点） ============

    static final String PURCHASING = GzJpFulfillStatus.PURCHASING.getCode();
    static final String PURCHASE_FAILED = GzJpFulfillStatus.PURCHASE_FAILED.getCode();
    static final String CUSTOMS = GzJpFulfillStatus.CUSTOMS.getCode();
    static final String DELIVERED = GzJpFulfillStatus.DELIVERED.getCode();
}
