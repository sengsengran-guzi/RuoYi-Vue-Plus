package org.dromara.gz.jp.job;

import org.dromara.gz.common.pay.domain.entity.GzPayTransaction;
import org.dromara.gz.common.pay.enums.PayStatus;
import org.dromara.gz.common.pay.service.IGzPayTransactionService.ReconcileOutcome;
import org.dromara.gz.jp.domain.entity.GzJpOrder;
import org.dromara.gz.jp.domain.enums.GzJpOrderStatus;
import org.dromara.gz.jp.job.GzJpUnpaidExpireJob.ExpireResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.dromara.gz.jp.job.GzJpUnpaidExpireFixture.query;
import static org.dromara.gz.jp.job.GzJpUnpaidExpireFixture.txn;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * GZ-JP-110 未付款超时关单 —— 时间阈值 / 微信侧已付款补推进 / 无支付流水（FLOW:F-JP-10.step1）。
 *
 * <p>accept #0 直接跑本类。反例实现「只看 create_time 不查微信支付单」会把已付款的单也关掉 →
 * {@link #wechatPaidIsRecoveredNotCancelled} 红。</p>
 */
@Tag("dev")
@DisplayName("GZ-JP-110 未付款超时关单：阈值 / 已付款补推进 / 无支付流水")
class GzJpUnpaidExpireJobTest {

    private GzJpUnpaidExpireFixture f;

    @BeforeEach
    void setUp() {
        f = new GzJpUnpaidExpireFixture();
    }

    @Test
    @DisplayName("超过 15 分钟未付款的单被关；未到阈值的、已非 created 的不动")
    void defaultThresholdCancelsOnlyExpired() {
        GzJpOrder expired = f.order(1L, 20);
        GzJpOrder fresh = f.order(2L, 10);
        GzJpOrder paidOld = f.order(3L, 60);
        paidOld.setBusinessStatus(GzJpOrderStatus.PAID.getCode());
        when(f.payTxMapper.selectList(any())).thenReturn(List.of());

        ExpireResult r = f.job().run();

        assertEquals(new ExpireResult(1, 1, 0, 0, 0), r);
        assertEquals("cancelled", f.statusOf(expired));
        assertNotNull(f.find(1L).getCancelledTime(), "关单要写 cancelled_time");
        assertEquals("created", f.statusOf(fresh), "10 分钟前下的单还没到 15 分钟，不能动");
        assertEquals("paid", f.statusOf(paidOld));
        verify(f.orderMapper, never()).markCancelled(eq(2L), any());
        verify(f.orderMapper, never()).markCancelled(eq(3L), any());

        // 阈值 = now - 15min
        long minutes = Duration.between(f.lastDeadline, LocalDateTime.now()).toMinutes();
        assertEquals(15, minutes);
    }

    @Test
    @DisplayName("阈值可配（gz.jp.order.unpaid-expire-minutes）：配 30 分钟时 20 分钟前的单不动")
    void thresholdIsConfigurable() {
        GzJpOrder order = f.order(1L, 20);

        ExpireResult r = f.job(30).run();

        assertEquals(new ExpireResult(0, 0, 0, 0, 0), r);
        assertEquals("created", f.statusOf(order));
        assertEquals(30, Duration.between(f.lastDeadline, LocalDateTime.now()).toMinutes());
        verify(f.wechatPayClient, never()).queryByOutTradeNo(anyString());
    }

    @Test
    @DisplayName("微信侧其实已付款（回调丢了）→ 走补单单点补推进成 paid，不关微信单、不关本地单")
    void wechatPaidIsRecoveredNotCancelled() {
        GzJpOrder order = f.order(1L, 20);
        GzPayTransaction t = txn(7001L, order, PayStatus.PENDING, "wx_prepay_7001");
        when(f.payTxMapper.selectList(any())).thenReturn(List.of(t));
        when(f.wechatPayClient.queryByOutTradeNo(t.getOutTradeNo())).thenReturn(query(t, "SUCCESS"));
        // reconcilePaid = 与回调同一路径（流水 paid + SPI → JpPayCallbackHandler → onPaid）；这里模拟其效果
        when(f.payTransactionService.reconcilePaid(eq(7001L), eq("42000000007001"), isNull(), anyString()))
            .thenAnswer(inv -> {
                f.find(1L).setBusinessStatus(GzJpOrderStatus.PAID.getCode());
                return ReconcileOutcome.PAID;
            });

        ExpireResult r = f.job().run();

        assertEquals(new ExpireResult(1, 0, 1, 0, 0), r);
        assertEquals("paid", f.statusOf(order));
        verify(f.payTransactionService).reconcilePaid(eq(7001L), eq("42000000007001"), isNull(), anyString());
        verify(f.wechatPayClient, never()).closeOrder(anyString());
        verify(f.orderMapper, never()).markCancelled(anyLong(), any());
    }

    @Test
    @DisplayName("本地流水已 paid 但订单仍 created → 重放 onPaid 补推进，不查单、不关单")
    void localPaidTxnReplaysOnPaid() {
        GzJpOrder order = f.order(1L, 20);
        GzPayTransaction t = txn(7001L, order, PayStatus.PAID, "wx_prepay_7001");
        when(f.payTxMapper.selectList(any())).thenReturn(List.of(t));

        ExpireResult r = f.job().run();

        assertEquals(new ExpireResult(1, 0, 1, 0, 0), r);
        verify(f.orderService).onPaid(t);
        verify(f.wechatPayClient, never()).queryByOutTradeNo(anyString());
        verify(f.wechatPayClient, never()).closeOrder(anyString());
        verify(f.orderMapper, never()).markCancelled(anyLong(), any());
    }

    @Test
    @DisplayName("迟到付款（本地流水已 timeout、微信说已付款）→ 不补推进、不关单、订单保持 created")
    void latePaidKeepsOrderOpen() {
        GzJpOrder order = f.order(1L, 20);
        GzPayTransaction t = txn(7001L, order, PayStatus.TIMEOUT, "wx_prepay_7001");
        when(f.payTxMapper.selectList(any())).thenReturn(List.of(t));
        when(f.wechatPayClient.queryByOutTradeNo(t.getOutTradeNo())).thenReturn(query(t, "SUCCESS"));

        ExpireResult r = f.job().run();

        assertEquals(new ExpireResult(1, 0, 0, 1, 0), r);
        assertEquals("created", f.statusOf(order));
        verify(f.payTransactionService, never()).reconcilePaid(anyLong(), anyString(), any(), anyString());
        verify(f.wechatPayClient, never()).closeOrder(anyString());
        verify(f.orderMapper, never()).markCancelled(anyLong(), any());
    }

    @Test
    @DisplayName("无支付流水（从未拉起支付）→ 不查单、不关微信单，直接关本地单")
    void noPayFlowCancelsDirectly() {
        GzJpOrder order = f.order(1L, 20);
        when(f.payTxMapper.selectList(any())).thenReturn(List.of());

        ExpireResult r = f.job().run();

        assertEquals(new ExpireResult(1, 1, 0, 0, 0), r);
        assertEquals("cancelled", f.statusOf(order));
        verify(f.wechatPayClient, never()).queryByOutTradeNo(anyString());
        verify(f.wechatPayClient, never()).closeOrder(anyString());
    }

    @Test
    @DisplayName("统一下单没成功的流水（无 prepay_id，微信侧没这单）等同无支付流水")
    void txnWithoutPrepayIsTreatedAsNoPayFlow() {
        GzJpOrder order = f.order(1L, 20);
        GzPayTransaction t = txn(7001L, order, PayStatus.FAILED, null);
        when(f.payTxMapper.selectList(any())).thenReturn(List.of(t));

        ExpireResult r = f.job().run();

        assertEquals(new ExpireResult(1, 1, 0, 0, 0), r);
        assertEquals("cancelled", f.statusOf(order));
        verify(f.wechatPayClient, never()).queryByOutTradeNo(anyString());
        verify(f.wechatPayClient, never()).closeOrder(anyString());
    }

    @Test
    @DisplayName("每单独立：前一单抛错只计 failed，后一单照常关")
    void oneOrderFailureDoesNotBlockOthers() {
        GzJpOrder broken = f.order(1L, 30);
        GzJpOrder ok = f.order(2L, 20);
        when(f.payTxMapper.selectList(any()))
            .thenThrow(new IllegalStateException("db hiccup"))
            .thenReturn(List.of());

        ExpireResult r = f.job().run();

        assertEquals(new ExpireResult(2, 1, 0, 0, 1), r);
        assertEquals("created", f.statusOf(broken));
        assertEquals("cancelled", f.statusOf(ok));
    }

    @Test
    @DisplayName("关单时回调抢先（markCancelled affected=0）→ 静默跳过，不算失败")
    void callbackWinsRaceIsSilentSkip() {
        GzJpOrder order = f.order(1L, 20);
        when(f.payTxMapper.selectList(any())).thenAnswer(inv -> {
            // 查流水之后、关单之前，回调把单推成了 paid
            f.find(1L).setBusinessStatus(GzJpOrderStatus.PAID.getCode());
            return List.of();
        });
        // 扫描时它还是 created
        ExpireResult r = f.job().run();

        assertEquals(new ExpireResult(1, 0, 0, 1, 0), r);
        assertEquals("paid", f.statusOf(order));
        assertTrue(f.find(1L).getCancelledTime() == null);
    }
}
