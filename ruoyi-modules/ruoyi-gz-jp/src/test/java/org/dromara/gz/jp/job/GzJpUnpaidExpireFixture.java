package org.dromara.gz.jp.job;

import org.dromara.gz.common.pay.domain.entity.GzPayTransaction;
import org.dromara.gz.common.pay.enums.PayBusinessType;
import org.dromara.gz.common.pay.mapper.GzPayTransactionMapper;
import org.dromara.gz.common.pay.service.IGzPayTransactionService;
import org.dromara.gz.common.pay.service.internal.IWechatPayClient;
import org.dromara.gz.common.pay.service.internal.IWechatPayClient.QueryResult;
import org.dromara.gz.jp.domain.entity.GzJpOrder;
import org.dromara.gz.jp.domain.enums.GzJpOrderStatus;
import org.dromara.gz.jp.mapper.GzJpOrderMapper;
import org.dromara.gz.jp.service.IGzJpOrderService;
import org.dromara.gz.jp.service.internal.GzJpUnpaidExpireTxService;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * GZ-JP-110 两个单测类共用的夹具。
 *
 * <p><b>订单表用 in-memory 假实现</b>（不是 when/thenReturn 桩）：{@code selectUnpaidExpired} 真按
 * {@code business_status='created' AND create_time < deadline} 过滤，{@code markCancelled} 真带
 * {@code WHERE business_status='created'} 守卫 —— 「未到阈值不动」「回调抢先 affected=0」这两条
 * 靠的就是这两处判断，换成桩就测了个寂寞。微信通道 / GZ-PAY 服务 / 支付流水 mapper 用 Mockito。</p>
 */
final class GzJpUnpaidExpireFixture {

    final GzJpOrderMapper orderMapper = mock(GzJpOrderMapper.class);
    final GzPayTransactionMapper payTxMapper = mock(GzPayTransactionMapper.class);
    final IWechatPayClient wechatPayClient = mock(IWechatPayClient.class);
    final IGzPayTransactionService payTransactionService = mock(IGzPayTransactionService.class);
    final IGzJpOrderService orderService = mock(IGzJpOrderService.class);
    final GzJpUnpaidExpireTxService txService = new GzJpUnpaidExpireTxService(orderMapper, orderService, org.mockito.Mockito.mock(org.dromara.gz.jp.service.internal.GzJpSnapLockService.class));

    /** in-memory gz_jp_order */
    final List<GzJpOrder> orders = new ArrayList<>();
    /** 最近一次扫描传进来的 deadline（断言阈值用） */
    LocalDateTime lastDeadline;

    GzJpUnpaidExpireFixture() {
        lenient().when(orderMapper.selectUnpaidExpired(any(LocalDateTime.class), anyInt())).thenAnswer(inv -> {
            LocalDateTime deadline = inv.getArgument(0);
            int limit = inv.getArgument(1);
            lastDeadline = deadline;
            return orders.stream()
                .filter(o -> GzJpOrderStatus.CREATED.getCode().equals(o.getBusinessStatus()))
                .filter(o -> toLocal(o.getCreateTime()).isBefore(deadline))
                .sorted(Comparator.comparing(GzJpOrder::getId))
                .limit(limit)
                .toList();
        });
        lenient().when(orderMapper.markCancelled(anyLong(), any(LocalDateTime.class))).thenAnswer(inv -> {
            Long id = inv.getArgument(0);
            GzJpOrder o = find(id);
            if (o == null || !GzJpOrderStatus.CREATED.getCode().equals(o.getBusinessStatus())) {
                return 0;
            }
            o.setBusinessStatus(GzJpOrderStatus.CANCELLED.getCode());
            o.setCancelledTime(inv.getArgument(1));
            return 1;
        });
    }

    GzJpUnpaidExpireJob job() {
        return job(GzJpUnpaidExpireJob.DEFAULT_UNPAID_EXPIRE_MINUTES);
    }

    GzJpUnpaidExpireJob job(int minutes) {
        return new GzJpUnpaidExpireJob(orderMapper, payTxMapper, wechatPayClient, payTransactionService, txService, minutes);
    }

    /** 造一张 created 订单，下单于 {@code minutesAgo} 分钟前。 */
    GzJpOrder order(long id, int minutesAgo) {
        GzJpOrder o = GzJpOrder.builder()
            .id(id)
            .orderNo("JPO-20261005-" + String.format("%06d", id))
            .userId(2001L)
            .totalAmountCent(9900L)
            .businessStatus(GzJpOrderStatus.CREATED.getCode())
            .version(0)
            .delFlag("0")
            .build();
        o.setCreateTime(Date.from(LocalDateTime.now().minusMinutes(minutesAgo).atZone(ZoneId.systemDefault()).toInstant()));
        orders.add(o);
        return o;
    }

    /** 造一笔挂在该订单上的支付流水（prepayId 为 null = 统一下单没成功，微信侧没有这单）。 */
    static GzPayTransaction txn(long id, GzJpOrder order, String status, String prepayId) {
        return GzPayTransaction.builder()
            .id(id)
            .outTradeNo("JP-20261005-" + String.format("%06d", id))
            .businessType(PayBusinessType.JP)
            .businessOrderNo(order.getOrderNo())
            .amountCent(order.getTotalAmountCent())
            .status(status)
            .prepayId(prepayId)
            .version(0)
            .build();
    }

    static QueryResult query(GzPayTransaction txn, String tradeState) {
        String txnId = "SUCCESS".equals(tradeState) ? "4200000000" + txn.getId() : null;
        return new QueryResult(txn.getOutTradeNo(), txnId, tradeState, null, null,
            "{\"out_trade_no\":\"" + txn.getOutTradeNo() + "\",\"trade_state\":\"" + tradeState + "\"}");
    }

    GzJpOrder find(Long id) {
        return orders.stream().filter(o -> o.getId().equals(id)).findFirst().orElse(null);
    }

    String statusOf(GzJpOrder o) {
        return find(o.getId()).getBusinessStatus();
    }

    private static LocalDateTime toLocal(Date d) {
        return LocalDateTime.ofInstant(d.toInstant(), ZoneId.systemDefault());
    }
}
