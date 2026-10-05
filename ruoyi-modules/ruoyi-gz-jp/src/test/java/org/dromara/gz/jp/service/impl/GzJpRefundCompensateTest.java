package org.dromara.gz.jp.service.impl;

import org.dromara.gz.common.pay.service.internal.IWechatPayClient.NotifyContext;
import org.dromara.gz.common.pay.service.internal.IWechatPayClient.RefundQueryResult;
import org.dromara.gz.common.pay.service.internal.IWechatPayClient.RefundRequest;
import org.dromara.gz.jp.domain.bo.GzJpMarkFailedBo;
import org.dromara.gz.jp.domain.entity.GzJpRefund;
import org.dromara.gz.jp.domain.enums.GzJpOrderStatus;
import org.dromara.gz.jp.domain.enums.GzJpRefundStatus;
import org.dromara.gz.jp.domain.enums.GzJpRefundType;
import org.dromara.gz.jp.job.GzJpRefundCompensateJob;
import org.dromara.gz.jp.job.GzJpRefundCompensateJob.CompensateResult;
import org.dromara.gz.jp.service.IGzJpRefundService.CompensateOutcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 退款卡单自动补偿（GZ-JP-111，FLOW:F-JP-10.step2，REQ-FULFILL-008）。
 *
 * <p><b>accept 第 1 条直接跑本类</b>：卡在 refunding 的退款单按微信查单结果补偿 ——
 * SUCCESS 走回调同一落账（幂等）/ ABNORMAL·CLOSED 落 refund_failed / NOT_FOUND <b>同号</b>补发 / PROCESSING 不动。</p>
 *
 * <p>数据层用 {@link GzJpRefundFixture} 的内存表（逐字照搬 SQL 守卫），微信通道是真的 {@code MockWechatPayClient}
 * （spy，只为断言补发时用的是哪个 out_refund_no）。退款单都走真实链路建出来（标购买失败 → 受理），
 * 再把 {@code update_time} 拨回 11 分钟前模拟「卡住」。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-JP-111)
 */
@Tag("dev")
@DisplayName("GZ-JP-111 退款卡单自动补偿")
class GzJpRefundCompensateTest {

    private static GzJpMarkFailedBo bo(Long... ids) {
        GzJpMarkFailedBo b = new GzJpMarkFailedBo();
        b.setItemIds(List.of(ids));
        return b;
    }

    private static GzJpRefundCompensateJob job(GzJpRefundFixture f) {
        return new GzJpRefundCompensateJob(f.refundMapper, f.service);
    }

    /** 走真实链路建一张已受理、卡住（update_time 11 分钟前）的退款单，并清掉建单期间的通道调用记录 */
    private static GzJpRefund stuck(GzJpRefundFixture f, long orderId, long itemId, long amount) {
        f.paidOrder(orderId, 14L, amount).item(itemId, orderId, amount, GzJpRefundFixture.PURCHASING);
        f.service.markPurchaseFailedAndRefund(bo(itemId), 1L, "admin");
        GzJpRefund refund = f.refundOf(itemId);
        assertNotNull(refund);
        assertEquals(GzJpRefundStatus.REFUNDING.getCode(), refund.getStatus());
        f.ageRefund(refund, 11);
        Mockito.clearInvocations(f.payClient);
        return refund;
    }

    private static NotifyContext ctx(String body) {
        return new NotifyContext("ts", "nonce", "sig", "serial", body);
    }

    // ============================================================
    //  SUCCESS → 回调同一落账
    // ============================================================

    @Test
    @DisplayName("SUCCESS：补落账（退款单 refunded + 行已退金额 + 订单 rollup），且绝不补发")
    void success_settlesViaCallbackPathAndNeverResends() {
        GzJpRefundFixture f = new GzJpRefundFixture();
        GzJpRefund refund = stuck(f, 1L, 11L, 10000L);
        int attemptsBefore = refund.getAttemptCount();
        f.payClient.setQueryRefundStatus(refund.getRefundNo(), RefundQueryResult.SUCCESS);

        CompensateResult r = job(f).run();

        assertEquals(1, r.scanned());
        assertEquals(1, r.count(CompensateOutcome.SETTLED));
        assertEquals(0, r.failed());
        assertEquals(GzJpRefundStatus.REFUNDED.getCode(), refund.getStatus());
        assertNotNull(refund.getRefundedTime());
        assertEquals("mock_refund_id_" + refund.getRefundNo(), refund.getWechatRefundId());
        assertEquals(GzJpRefundStatus.REFUNDED.getCode(), f.row(11L).getRefundStatus());
        assertEquals(10000L, f.row(11L).getRefundAmountCent(), "行上已退金额 = 该行金额");
        assertEquals(GzJpOrderStatus.REFUNDED.getCode(), f.orderStatus(1L), "单行订单退完 → refunded");
        assertEquals(attemptsBefore, refund.getAttemptCount(), "落账不是补发，提交次数不变");
        verify(f.payClient, times(1)).queryRefund(refund.getRefundNo());
        verify(f.payClient, never()).refund(any());
    }

    @Test
    @DisplayName("SUCCESS 但回调先到：补偿走同一落账方法 affected=0 → 不重复写行、不重复 rollup")
    void success_callbackFirst_noDoubleSettle() {
        GzJpRefundFixture f = new GzJpRefundFixture();
        // 两行订单：先退第 1 行 → partial_refunded，再看补偿会不会把它多 rollup 一次
        f.paidOrder(1L, 14L, 20000L)
            .item(11L, 1L, 10000L, GzJpRefundFixture.PURCHASING)
            .item(12L, 1L, 10000L, GzJpRefundFixture.PURCHASING);
        f.service.markPurchaseFailedAndRefund(bo(11L), 1L, "admin");
        GzJpRefund refund = f.refundOf(11L);
        f.ageRefund(refund, 11);
        // 调度扫描时它还卡着（快照），随后回调到达并落账
        List<GzJpRefund> scanned = f.refundMapper.selectStaleRefunding(java.time.LocalDateTime.now().minusMinutes(10), 200);
        assertEquals(1, scanned.size());
        assertTrue(f.service.handleRefundNotify(ctx(f.callbackBody(refund, "SUCCESS"))));
        assertEquals(GzJpOrderStatus.PARTIAL_REFUNDED.getCode(), f.orderStatus(1L));
        int refundVersion = refund.getVersion();
        int itemVersion = f.row(11L).getVersion();
        int orderVersion = f.orders.get(1L).getVersion();

        f.payClient.setQueryRefundStatus(refund.getRefundNo(), RefundQueryResult.SUCCESS);
        Mockito.clearInvocations(f.payClient);
        CompensateOutcome outcome = f.service.compensateStaleRefund(scanned.get(0));

        assertEquals(CompensateOutcome.ALREADY_SETTLED, outcome);
        assertEquals(refundVersion, refund.getVersion(), "退款单不被二次推进");
        assertEquals(itemVersion, f.row(11L).getVersion(), "行不被重复写");
        assertEquals(orderVersion, f.orders.get(1L).getVersion(), "订单不被重复 rollup");
        assertEquals(GzJpOrderStatus.PARTIAL_REFUNDED.getCode(), f.orderStatus(1L));
        verify(f.payClient, never()).refund(any());
    }

    @Test
    @DisplayName("SUCCESS 补偿先落账、回调后到：回调当重复处理，订单只 rollup 一次")
    void success_compensateFirst_callbackIsDuplicate() {
        GzJpRefundFixture f = new GzJpRefundFixture();
        GzJpRefund refund = stuck(f, 1L, 11L, 10000L);
        f.payClient.setQueryRefundStatus(refund.getRefundNo(), RefundQueryResult.SUCCESS);
        assertEquals(1, job(f).run().count(CompensateOutcome.SETTLED));
        int itemVersion = f.row(11L).getVersion();
        int orderVersion = f.orders.get(1L).getVersion();

        assertTrue(f.service.handleRefundNotify(ctx(f.callbackBody(refund, "SUCCESS"))), "重复回调仍回成功");

        assertEquals(itemVersion, f.row(11L).getVersion());
        assertEquals(orderVersion, f.orders.get(1L).getVersion());
        assertEquals(GzJpOrderStatus.REFUNDED.getCode(), f.orderStatus(1L));
        assertEquals(0, job(f).run().scanned(), "已终态的单不会再被扫到");
    }

    // ============================================================
    //  ABNORMAL / CLOSED → refund_failed
    // ============================================================

    @ParameterizedTest(name = "{0}：落 refund_failed + fail_reason，订单不 rollup，不补发")
    @ValueSource(strings = {"ABNORMAL", "CLOSED"})
    void abnormalOrClosed_markedFailed(String status) {
        GzJpRefundFixture f = new GzJpRefundFixture();
        GzJpRefund refund = stuck(f, 1L, 11L, 10000L);
        f.payClient.setQueryRefundStatus(refund.getRefundNo(), status);

        CompensateResult r = job(f).run();

        assertEquals(1, r.count(CompensateOutcome.MARKED_FAILED));
        assertEquals(GzJpRefundStatus.REFUND_FAILED.getCode(), refund.getStatus());
        assertNotNull(refund.getFailReason());
        assertTrue(refund.getFailReason().contains(status), "fail_reason 带微信状态：" + refund.getFailReason());
        assertEquals(GzJpRefundStatus.REFUND_FAILED.getCode(), f.row(11L).getRefundStatus());
        assertNull(f.row(11L).getRefundAmountCent(), "钱没退出去，行上不落已退金额");
        assertEquals(GzJpOrderStatus.PAID.getCode(), f.orderStatus(1L), "不 rollup");
        verify(f.payClient, never()).refund(any());
    }

    // ============================================================
    //  NOT_FOUND → 同一 refund_no 补发
    // ============================================================

    @Test
    @DisplayName("NOT_FOUND：用同一个 refund_no 补发（attempt_count+1，仍 refunding 等回调），不新建退款单")
    void notFound_resendsWithSameRefundNo() {
        GzJpRefundFixture f = new GzJpRefundFixture();
        GzJpRefund refund = stuck(f, 1L, 11L, 10000L);
        String refundNo = refund.getRefundNo();
        int attemptsBefore = refund.getAttemptCount();
        int refundRows = f.refunds.size();
        // 模拟「建单后、提交微信前进程挂了」：微信侧没有这张单
        f.payClient.setQueryRefundStatus(refundNo, RefundQueryResult.NOT_FOUND);

        CompensateResult r = job(f).run();

        assertEquals(1, r.count(CompensateOutcome.RESENT));
        ArgumentCaptor<RefundRequest> cap = ArgumentCaptor.forClass(RefundRequest.class);
        verify(f.payClient, times(1)).refund(cap.capture());
        RefundRequest sent = cap.getValue();
        assertEquals(refundNo, sent.outRefundNo(), "★ 补发必须复用原 refund_no（换号 = 双退）");
        assertEquals(refund.getOutTradeNo(), sent.outTradeNo());
        assertEquals(10000L, sent.refundAmountCent(), "退的仍是该行金额");
        assertEquals(refund.getTotalAmountCent(), sent.totalAmountCent());

        assertEquals(refundRows, f.refunds.size(), "不新建退款单");
        assertEquals(GzJpRefundStatus.REFUNDING.getCode(), refund.getStatus(), "补发受理后仍等回调");
        assertEquals(attemptsBefore + 1, refund.getAttemptCount(), "attempt_count + 1");
        assertEquals("mock_refund_id_" + refundNo, refund.getWechatRefundId());
        assertEquals(RefundQueryResult.PROCESSING, f.payClient.queryRefund(refundNo).status(), "微信侧已有这张单");
        assertEquals(GzJpOrderStatus.PAID.getCode(), f.orderStatus(1L), "补发 ≠ 已退，不 rollup");

        Mockito.clearInvocations(f.payClient);
        assertEquals(0, job(f).run().scanned(), "补发刷新了 update_time，下一轮不会立刻再查 / 再补发");
        verify(f.payClient, never()).refund(any());
    }

    @Test
    @DisplayName("NOT_FOUND 补发受理失败：落 refund_failed + fail_reason（留 admin 重新发起），不静默")
    void notFound_resendFails_markedFailed() {
        GzJpRefundFixture f = new GzJpRefundFixture();
        GzJpRefund refund = stuck(f, 1L, 11L, 10000L);
        f.payClient.setQueryRefundStatus(refund.getRefundNo(), RefundQueryResult.NOT_FOUND);
        f.payClient.setRefundAcceptFail(true);

        CompensateResult r = job(f).run();

        assertEquals(1, r.count(CompensateOutcome.RESEND_FAILED));
        assertEquals(GzJpRefundStatus.REFUND_FAILED.getCode(), refund.getStatus());
        assertTrue(refund.getFailReason().contains("补发失败"), refund.getFailReason());
        assertEquals(GzJpRefundStatus.REFUND_FAILED.getCode(), f.row(11L).getRefundStatus());
        verify(f.payClient, times(1)).refund(any());
    }

    // ============================================================
    //  PROCESSING → 不动
    // ============================================================

    @Test
    @DisplayName("PROCESSING：什么都不改、不补发")
    void processing_untouched() {
        GzJpRefundFixture f = new GzJpRefundFixture();
        GzJpRefund refund = stuck(f, 1L, 11L, 10000L);
        int version = refund.getVersion();
        int attempts = refund.getAttemptCount();
        Date updateTime = refund.getUpdateTime();

        CompensateResult r = job(f).run();

        assertEquals(1, r.count(CompensateOutcome.PROCESSING));
        assertEquals(GzJpRefundStatus.REFUNDING.getCode(), refund.getStatus());
        assertEquals(version, refund.getVersion());
        assertEquals(attempts, refund.getAttemptCount());
        assertEquals(updateTime, refund.getUpdateTime());
        assertEquals(GzJpRefundStatus.REFUNDING.getCode(), f.row(11L).getRefundStatus());
        verify(f.payClient, never()).refund(any());
    }

    // ============================================================
    //  扫描口径 / 每单独立
    // ============================================================

    @Test
    @DisplayName("未过静默期（update_time 5 分钟前）的 refunding 单不扫、不查单")
    void notStale_notScanned() {
        GzJpRefundFixture f = new GzJpRefundFixture();
        GzJpRefund refund = stuck(f, 1L, 11L, 10000L);
        f.ageRefund(refund, 5);

        CompensateResult r = job(f).run();

        assertEquals(0, r.scanned());
        verify(f.payClient, never()).queryRefund(anyString());
        verify(f.payClient, never()).refund(any());
    }

    @Test
    @DisplayName("查单失败：本单计 failed、保持 refunding、绝不当成 NOT_FOUND 去补发；同轮其他单照常处理")
    void queryFails_skippedAndOthersContinue() {
        GzJpRefundFixture f = new GzJpRefundFixture();
        GzJpRefund first = stuck(f, 1L, 11L, 10000L);
        GzJpRefund second = stuck(f, 2L, 21L, 5000L);
        f.payClient.setQueryRefundStatus(second.getRefundNo(), RefundQueryResult.SUCCESS);
        // 第一张查单抛异常，第二张正常
        Mockito.doThrow(new IllegalStateException("微信 5xx（模拟）"))
            .when(f.payClient).queryRefund(first.getRefundNo());

        CompensateResult r = job(f).run();

        assertEquals(2, r.scanned());
        assertEquals(1, r.failed());
        assertEquals(1, r.count(CompensateOutcome.SETTLED));
        assertEquals(GzJpRefundStatus.REFUNDING.getCode(), first.getStatus(), "查单失败的单留到下一轮");
        assertEquals(GzJpRefundStatus.REFUNDED.getCode(), second.getStatus());
        verify(f.payClient, never()).refund(any());
    }

    @Test
    @DisplayName("不区分 refund_type：运费多退（shipping_diff）的卡单同样被扫到并查单")
    void bothRefundTypesScanned() {
        GzJpRefundFixture f = new GzJpRefundFixture();
        GzJpRefund purchaseFailed = stuck(f, 1L, 11L, 10000L);
        GzJpRefund shippingDiff = new GzJpRefund();
        shippingDiff.setRefundNo("JPRF-20260807-900001");
        shippingDiff.setRefundType(GzJpRefundType.SHIPPING_DIFF.getCode());
        shippingDiff.setOrderId(1L);
        shippingDiff.setOrderItemId(11L);
        shippingDiff.setUserId(14L);
        shippingDiff.setOutTradeNo(purchaseFailed.getOutTradeNo());
        shippingDiff.setRefundAmountCent(800L);
        shippingDiff.setTotalAmountCent(purchaseFailed.getTotalAmountCent());
        shippingDiff.setStatus(GzJpRefundStatus.REFUNDING.getCode());
        shippingDiff.setReason("到付预估运费多退");
        shippingDiff.setAttemptCount(1);
        shippingDiff.setVersion(1);
        shippingDiff.setDelFlag("0");
        f.refundMapper.insert(shippingDiff);
        f.ageRefund(shippingDiff, 30);
        f.payClient.setQueryRefundStatus(shippingDiff.getRefundNo(), RefundQueryResult.PROCESSING);

        CompensateResult r = job(f).run();

        assertEquals(2, r.scanned());
        assertEquals(2, r.count(CompensateOutcome.PROCESSING));
        verify(f.payClient).queryRefund(purchaseFailed.getRefundNo());
        verify(f.payClient).queryRefund(shippingDiff.getRefundNo());
    }
}
