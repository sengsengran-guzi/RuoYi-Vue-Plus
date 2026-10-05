package org.dromara.gz.jp.job;

import org.dromara.gz.common.pay.domain.entity.GzPayTransaction;
import org.dromara.gz.common.pay.enums.PayStatus;
import org.dromara.gz.common.pay.service.IGzPayTransactionService.ReconcileOutcome;
import org.dromara.gz.jp.domain.entity.GzJpOrder;
import org.dromara.gz.jp.job.GzJpUnpaidExpireJob.ExpireResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.LocalDateTime;
import java.util.List;

import static org.dromara.gz.jp.job.GzJpUnpaidExpireFixture.query;
import static org.dromara.gz.jp.job.GzJpUnpaidExpireFixture.txn;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * GZ-JP-110 关单顺序：查单 → 关微信单 → 关本地单；任一步失败不落本地 cancelled（FLOW:F-JP-10.step1 ②③）。
 *
 * <p>accept #1 直接跑本类。反例「跳过 closeOrder 直接 markCancelled」「closeOrder 抛错仍置 cancelled」都会红 ——
 * 那样孤品锁放给了别人，客人却还能把这单付掉。</p>
 */
@Tag("dev")
@DisplayName("GZ-JP-110 关单顺序：查单 → 关微信单 → 关本地单，关失败不取消")
class GzJpUnpaidExpireCloseOrderTest {

    private GzJpUnpaidExpireFixture f;
    private GzJpOrder order;
    private GzPayTransaction t;

    @BeforeEach
    void setUp() {
        f = new GzJpUnpaidExpireFixture();
        order = f.order(1L, 20);
        t = txn(7001L, order, PayStatus.PENDING, "wx_prepay_7001");
        when(f.payTxMapper.selectList(any())).thenReturn(List.of(t));
    }

    @Test
    @DisplayName("未付款：查单 → closeOrder → markCancelled，顺序固定")
    void closeOrderBeforeMarkCancelled() {
        when(f.wechatPayClient.queryByOutTradeNo(t.getOutTradeNo())).thenReturn(query(t, "NOTPAY"));

        ExpireResult r = f.job().run();

        assertEquals(new ExpireResult(1, 1, 0, 0, 0), r);
        assertEquals("cancelled", f.statusOf(order));
        InOrder seq = inOrder(f.wechatPayClient, f.orderMapper);
        seq.verify(f.wechatPayClient).queryByOutTradeNo(t.getOutTradeNo());
        seq.verify(f.wechatPayClient).closeOrder(t.getOutTradeNo());
        seq.verify(f.orderMapper).markCancelled(eq(1L), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("closeOrder 失败 → 本地不取消；下一轮关成功才取消")
    void closeFailureKeepsOrderThenRetriesNextRound() {
        when(f.wechatPayClient.queryByOutTradeNo(t.getOutTradeNo())).thenReturn(query(t, "NOTPAY"));
        doThrow(new IllegalStateException("SYSTEM_ERROR 微信系统繁忙"))
            .doNothing()
            .when(f.wechatPayClient).closeOrder(t.getOutTradeNo());

        ExpireResult first = f.job().run();
        assertEquals(new ExpireResult(1, 0, 0, 1, 0), first);
        assertEquals("created", f.statusOf(order), "微信单没关掉，本地绝不能先取消");
        verify(f.orderMapper, never()).markCancelled(anyLong(), any());

        ExpireResult second = f.job().run();
        assertEquals(new ExpireResult(1, 1, 0, 0, 0), second);
        assertEquals("cancelled", f.statusOf(order));
    }

    @Test
    @DisplayName("closeOrder 返回 ORDERPAID（查单后客人刚付了）→ 本地不取消")
    void orderPaidOnCloseDoesNotCancel() {
        when(f.wechatPayClient.queryByOutTradeNo(t.getOutTradeNo())).thenReturn(query(t, "NOTPAY"));
        doThrow(new IllegalStateException("ORDERPAID 订单已支付"))
            .when(f.wechatPayClient).closeOrder(t.getOutTradeNo());

        ExpireResult r = f.job().run();

        assertEquals(new ExpireResult(1, 0, 0, 1, 0), r);
        assertEquals("created", f.statusOf(order));
        verify(f.orderMapper, never()).markCancelled(anyLong(), any());
    }

    @Test
    @DisplayName("查单失败 → 不关微信单、不关本地单")
    void queryFailureSkipsWholeOrder() {
        when(f.wechatPayClient.queryByOutTradeNo(t.getOutTradeNo())).thenThrow(new IllegalStateException("timeout"));

        ExpireResult r = f.job().run();

        assertEquals(new ExpireResult(1, 0, 0, 1, 0), r);
        assertEquals("created", f.statusOf(order));
        verify(f.wechatPayClient, never()).closeOrder(anyString());
        verify(f.orderMapper, never()).markCancelled(anyLong(), any());
    }

    @Test
    @DisplayName("微信侧已关（time_expire 到点）→ 不必再 closeOrder，直接关本地单")
    void alreadyClosedOnWechatSkipsCloseCall() {
        when(f.wechatPayClient.queryByOutTradeNo(t.getOutTradeNo())).thenReturn(query(t, "CLOSED"));

        ExpireResult r = f.job().run();

        assertEquals(new ExpireResult(1, 1, 0, 0, 0), r);
        assertEquals("cancelled", f.statusOf(order));
        verify(f.wechatPayClient, never()).closeOrder(anyString());
    }

    @Test
    @DisplayName("本地流水已 timeout、微信仍 NOTPAY（106 之前的在途单）→ 兜底再关一次再取消")
    void timeoutTxnStillNotPaidIsClosedAgain() {
        GzPayTransaction old = txn(7002L, order, PayStatus.TIMEOUT, "wx_prepay_7002");
        when(f.payTxMapper.selectList(any())).thenReturn(List.of(old));
        when(f.wechatPayClient.queryByOutTradeNo(old.getOutTradeNo())).thenReturn(query(old, "NOTPAY"));
        doNothing().when(f.wechatPayClient).closeOrder(old.getOutTradeNo());

        ExpireResult r = f.job().run();

        assertEquals(new ExpireResult(1, 1, 0, 0, 0), r);
        InOrder seq = inOrder(f.wechatPayClient, f.orderMapper);
        seq.verify(f.wechatPayClient).closeOrder(old.getOutTradeNo());
        seq.verify(f.orderMapper).markCancelled(eq(1L), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("多笔流水：前一笔 NOTPAY、后一笔已付款 → 一笔都不关，补推进")
    void anyPaidTxnPreventsClosingOthers() {
        GzPayTransaction second = txn(7002L, order, PayStatus.PENDING, "wx_prepay_7002");
        when(f.payTxMapper.selectList(any())).thenReturn(List.of(t, second));
        when(f.wechatPayClient.queryByOutTradeNo(t.getOutTradeNo())).thenReturn(query(t, "NOTPAY"));
        when(f.wechatPayClient.queryByOutTradeNo(second.getOutTradeNo())).thenReturn(query(second, "SUCCESS"));
        when(f.payTransactionService.reconcilePaid(eq(7002L), anyString(), any(), anyString()))
            .thenReturn(ReconcileOutcome.PAID);

        ExpireResult r = f.job().run();

        assertEquals(new ExpireResult(1, 0, 1, 0, 0), r);
        verify(f.wechatPayClient, never()).closeOrder(anyString());
        verify(f.orderMapper, never()).markCancelled(anyLong(), any());
    }

    @Test
    @DisplayName("客人支付中（USERPAYING）→ 本轮不关")
    void userPayingIsNotClosed() {
        when(f.wechatPayClient.queryByOutTradeNo(t.getOutTradeNo())).thenReturn(query(t, "USERPAYING"));

        ExpireResult r = f.job().run();

        assertEquals(new ExpireResult(1, 0, 0, 1, 0), r);
        assertEquals("created", f.statusOf(order));
        verify(f.wechatPayClient, never()).closeOrder(anyString());
        verify(f.orderMapper, never()).markCancelled(anyLong(), any());
    }
}
