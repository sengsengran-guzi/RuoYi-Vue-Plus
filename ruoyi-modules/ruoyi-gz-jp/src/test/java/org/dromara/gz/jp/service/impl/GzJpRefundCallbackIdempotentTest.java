package org.dromara.gz.jp.service.impl;

import org.dromara.gz.common.pay.service.internal.IWechatPayClient.NotifyContext;
import org.dromara.gz.common.pay.service.internal.WechatPayVerifyException;
import org.dromara.gz.jp.domain.bo.GzJpMarkFailedBo;
import org.dromara.gz.jp.domain.entity.GzJpRefund;
import org.dromara.gz.jp.domain.enums.GzJpOrderStatus;
import org.dromara.gz.jp.domain.enums.GzJpRefundStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 退款回调幂等单测（GZ-JP-107，FLOW:F-JP-04.step3）。
 *
 * <p><b>accept 第 3 条直接跑本类</b>（「退款回调幂等：重复回调不重复退」）。</p>
 *
 * <p><b>为什么这条必须测到骨头里</b>：微信退款回调<b>重发是常态不是异常</b>（网络抖动 /
 * 我方响应慢 / 微信自身重试策略），一次退款收到 2-3 次回调很正常。幂等做漏了的后果不是数据难看：
 * 是订单被重复 rollup、行上的已退金额被重复写、审计日志分不清哪次才算数 ——
 * 而这些恰恰是对账时唯一的凭据。</p>
 *
 * <p>幂等靠<b>两层</b>：① 锁退款单行后判状态（非 refunding 直接当重复）；
 * ② {@code UPDATE ... WHERE status='refunding'} 的守卫（affected=0 = 并发抢先，也当重复）。
 * 本类两层都单独打过。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-JP-107)
 */
@Tag("dev")
@DisplayName("GZ-JP-107 退款回调幂等：重复回调不重复退")
class GzJpRefundCallbackIdempotentTest {

    private static GzJpMarkFailedBo bo(Long... ids) {
        GzJpMarkFailedBo b = new GzJpMarkFailedBo();
        b.setItemIds(List.of(ids));
        return b;
    }

    private static NotifyContext ctx(String body) {
        return new NotifyContext("ts", "nonce", "sig", "serial", body);
    }

    /** 建一张已受理的退款单（走真实链路，不是手工塞数据） */
    private static GzJpRefund arrange(GzJpRefundFixture f, long orderId, long itemId, long amount) {
        f.paidOrder(orderId, 14L, amount).item(itemId, orderId, amount, GzJpRefundFixture.PURCHASING);
        f.service.markPurchaseFailedAndRefund(bo(itemId), 1L, "admin");
        GzJpRefund refund = f.refundOf(itemId);
        assertNotNull(refund);
        assertEquals(GzJpRefundStatus.REFUNDING.getCode(), refund.getStatus());
        return refund;
    }

    // ============================================================
    //  accept[3] 主场景
    // ============================================================

    @Test
    @DisplayName("accept[3] 同一个回调收 3 次：只推进一次，金额只写一次，订单只 rollup 一次")
    void duplicateCallbackProcessedOnce() {
        GzJpRefundFixture f = new GzJpRefundFixture();
        GzJpRefund refund = arrange(f, 1L, 11L, 10000L);
        String body = f.callbackBody(refund, "SUCCESS");

        assertTrue(f.service.handleRefundNotify(ctx(body)), "第 1 次：正常处理");
        int versionAfterFirst = f.refundOf(11L).getVersion();
        int itemVersionAfterFirst = f.row(11L).getVersion();
        int orderVersionAfterFirst = f.orders.get(1L).getVersion();

        assertTrue(f.service.handleRefundNotify(ctx(body)), "第 2 次：必须仍回 true（否则微信会一直重试）");
        assertTrue(f.service.handleRefundNotify(ctx(body)), "第 3 次：同上");

        assertEquals(GzJpRefundStatus.REFUNDED.getCode(), f.refundOf(11L).getStatus());
        assertEquals(10000L, f.row(11L).getRefundAmountCent());
        assertEquals(GzJpOrderStatus.REFUNDED.getCode(), f.orderStatus(1L));

        // ★ 版本号是"到底写了几次"的硬证据 —— 重复回调后三张表的版本都不该再动
        assertEquals(versionAfterFirst, f.refundOf(11L).getVersion(), "退款单被写了不止一次");
        assertEquals(itemVersionAfterFirst, f.row(11L).getVersion(), "商品行被写了不止一次");
        assertEquals(orderVersionAfterFirst, f.orders.get(1L).getVersion(), "订单被 rollup 了不止一次");
        assertEquals(1, f.refunds.size());
        System.out.println("[accept3] 同一回调收 3 次：退款单/商品行/订单 version 均只 +1 次（"
            + versionAfterFirst + "/" + itemVersionAfterFirst + "/" + orderVersionAfterFirst + "），全程 true");
    }

    @Test
    @DisplayName("accept[3] 一单多行：各行回调各归各，重复的那次不会污染别的行")
    void duplicateCallbackDoesNotAffectSiblingLines() {
        GzJpRefundFixture f = new GzJpRefundFixture();
        f.paidOrder(2L, 14L, 30000L)
            .item(21L, 2L, 10000L, GzJpRefundFixture.PURCHASING)
            .item(22L, 2L, 20000L, GzJpRefundFixture.PURCHASING);
        f.service.markPurchaseFailedAndRefund(bo(21L, 22L), 1L, "admin");

        GzJpRefund r21 = f.refundOf(21L);
        GzJpRefund r22 = f.refundOf(22L);

        // 21 的回调连来 3 次
        for (int i = 0; i < 3; i++) {
            assertTrue(f.service.handleRefundNotify(ctx(f.callbackBody(r21, "SUCCESS"))));
        }
        assertEquals(GzJpRefundStatus.REFUNDED.getCode(), f.refundOf(21L).getStatus());
        assertEquals(GzJpRefundStatus.REFUNDING.getCode(), f.refundOf(22L).getStatus(), "22 完全没被碰过");
        assertNull(f.row(22L).getRefundAmountCent());
        assertEquals(GzJpOrderStatus.PARTIAL_REFUNDED.getCode(), f.orderStatus(2L), "只退了 1/2 → 部分退款");

        // 22 的回调到了才是全退
        assertTrue(f.service.handleRefundNotify(ctx(f.callbackBody(r22, "SUCCESS"))));
        assertEquals(GzJpOrderStatus.REFUNDED.getCode(), f.orderStatus(2L));
        assertEquals(10000L, f.row(21L).getRefundAmountCent());
        assertEquals(20000L, f.row(22L).getRefundAmountCent());
        System.out.println("[accept3 多行] 21 回调 ×3 + 22 回调 ×1：金额各归各（10000/20000），订单 partial → refunded");
    }

    @Test
    @DisplayName("accept[3] 并发回调：守卫 UPDATE affected=0 的那次当重复处理，不重复 rollup")
    void concurrentCallbackGuardedByStatusWhere() {
        GzJpRefundFixture f = new GzJpRefundFixture();
        GzJpRefund refund = arrange(f, 3L, 31L, 10000L);

        // 模拟"并发的另一个回调已经抢先推进"：先把退款单推成终态，再让本次回调进来
        f.txService.applyRefundSuccess(refund, "mock_refund_id_first");
        int orderVersion = f.orders.get(3L).getVersion();
        assertEquals(GzJpOrderStatus.REFUNDED.getCode(), f.orderStatus(3L));

        assertTrue(f.service.handleRefundNotify(ctx(f.callbackBody(refund, "SUCCESS"))),
            "抢输的那次也要回 true —— 回 false 会让微信一直重试一个已经处理好的回调");
        assertEquals(orderVersion, f.orders.get(3L).getVersion(), "订单不能被第二次 rollup");

        // 直接打第二层守卫：txService 再来一次必须返回 false（affected=0）
        assertFalse(f.txService.applyRefundSuccess(refund, "mock_refund_id_second"),
            "★ WHERE status='refunding' 守卫必须挡住第二次推进");
        assertEquals("mock_refund_id_first", f.refundOf(31L).getWechatRefundId(), "微信单号不该被后到的覆盖");
        System.out.println("[accept3 并发] 抢先推进后：回调仍回 true、订单 version 不变、"
            + "applyRefundSuccess 第二次返回 false");
    }

    // ============================================================
    //  失败回调 / 异常回调
    // ============================================================

    @Test
    @DisplayName("回调 ABNORMAL：标 refund_failed 留人工，且【不】rollup（钱没退出去）")
    void abnormalCallbackMarksFailedWithoutRollup() {
        GzJpRefundFixture f = new GzJpRefundFixture();
        GzJpRefund refund = arrange(f, 4L, 41L, 10000L);

        assertTrue(f.service.handleRefundNotify(ctx(f.callbackBody(refund, "ABNORMAL"))));

        assertEquals(GzJpRefundStatus.REFUND_FAILED.getCode(), f.refundOf(41L).getStatus());
        assertTrue(f.refundOf(41L).getFailReason().contains("ABNORMAL"), "★ 失败原因必须落库，admin 才看得到");
        assertEquals(GzJpRefundStatus.REFUND_FAILED.getCode(), f.row(41L).getRefundStatus());
        assertNull(f.row(41L).getRefundAmountCent(), "没退成功就不该有已退金额");
        assertEquals(GzJpOrderStatus.PAID.getCode(), f.orderStatus(4L), "★ 钱没退出去，订单不该显示已退款");
        // 行的履约事实不动（一期无回退）
        assertEquals(GzJpRefundFixture.PURCHASE_FAILED, f.row(41L).getFulfillStatus());
        System.out.println("[ABNORMAL] refund_failed + 原因落库；订单仍 paid；行仍 purchase_failed");
    }

    @Test
    @DisplayName("失败回调也幂等：ABNORMAL 收 3 次只写一次")
    void abnormalCallbackIdempotent() {
        GzJpRefundFixture f = new GzJpRefundFixture();
        GzJpRefund refund = arrange(f, 5L, 51L, 10000L);
        String body = f.callbackBody(refund, "ABNORMAL");

        assertTrue(f.service.handleRefundNotify(ctx(body)));
        int v = f.refundOf(51L).getVersion();
        int iv = f.row(51L).getVersion();
        assertTrue(f.service.handleRefundNotify(ctx(body)));
        assertTrue(f.service.handleRefundNotify(ctx(body)));
        assertEquals(v, f.refundOf(51L).getVersion());
        assertEquals(iv, f.row(51L).getVersion());
        System.out.println("[ABNORMAL 幂等] 收 3 次：退款单 version 停在 " + v + "，商品行停在 " + iv);
    }

    @Test
    @DisplayName("先 ABNORMAL 后 SUCCESS（微信改判）：已终态不再翻转，留 admin 重试")
    void failedThenSuccessDoesNotFlipSilently() {
        GzJpRefundFixture f = new GzJpRefundFixture();
        GzJpRefund refund = arrange(f, 6L, 61L, 10000L);

        assertTrue(f.service.handleRefundNotify(ctx(f.callbackBody(refund, "ABNORMAL"))));
        assertEquals(GzJpRefundStatus.REFUND_FAILED.getCode(), f.refundOf(61L).getStatus());

        // 迟到的 SUCCESS：退款单已是终态 → 当重复回调返回 true，但不静默改成已退款
        assertTrue(f.service.handleRefundNotify(ctx(f.callbackBody(refund, "SUCCESS"))));
        assertEquals(GzJpRefundStatus.REFUND_FAILED.getCode(), f.refundOf(61L).getStatus(),
            "终态不被后到的回调翻转 —— 要改得走 admin 重试，留下操作痕迹");
        assertEquals(GzJpOrderStatus.PAID.getCode(), f.orderStatus(6L));
        System.out.println("[终态不翻转] ABNORMAL 之后到的 SUCCESS 被当重复回调，状态与订单都不动");
    }

    // ============================================================
    //  异常输入
    // ============================================================

    @Test
    @DisplayName("回调里的 out_refund_no 在拼团退款单表里不存在 → 返回 false（500 让微信重试）")
    void unknownRefundNoReturnsFalse() {
        GzJpRefundFixture f = new GzJpRefundFixture();
        String body = f.payClient.buildMockRefundCallbackBody(
            "JPO-20260807-000002", "RF-20260807-000001", "mock_refund_id_x", "SUCCESS");

        assertFalse(f.service.handleRefundNotify(ctx(body)),
            "找不到退款单必须回 false —— 静默回 true 会让一笔真实退款永远没人发现");
        System.out.println("[未知单号] RF- 开头的 GZ-PAY 单打到拼团回调地址 → false（并写 failed 审计日志）");
    }

    @Test
    @DisplayName("body 缺字段 → 抛验签/解析异常（controller 转 401，不静默吞）")
    void malformedBodyThrows() {
        GzJpRefundFixture f = new GzJpRefundFixture();
        assertThrows(WechatPayVerifyException.class,
            () -> f.service.handleRefundNotify(ctx("{\"out_trade_no\":\"X\"}")));
        assertThrows(WechatPayVerifyException.class,
            () -> f.service.handleRefundNotify(ctx("not-a-json")));
        System.out.println("[坏 body] 缺 out_refund_no / 非 JSON → WechatPayVerifyException（401）");
    }

    // ============================================================
    //  重试与回调的交叉
    // ============================================================

    @Test
    @DisplayName("重试后旧的失败回调再来一次不会把状态打回 refund_failed")
    void staleFailureCallbackAfterRetry() {
        GzJpRefundFixture f = new GzJpRefundFixture();
        f.paidOrder(7L, 14L, 10000L).item(71L, 7L, 10000L, GzJpRefundFixture.PURCHASING);
        f.payClient.setRefundAcceptFail(true);
        f.service.markPurchaseFailedAndRefund(bo(71L), 1L, "admin");
        GzJpRefund refund = f.refundOf(71L);
        assertEquals(GzJpRefundStatus.REFUND_FAILED.getCode(), refund.getStatus());

        // admin 重试 → refunding → 成功回调
        f.payClient.setRefundAcceptFail(false);
        f.service.retry(refund.getId(), "admin");
        assertTrue(f.service.handleRefundNotify(ctx(f.callbackBody(refund, "SUCCESS"))));
        assertEquals(GzJpRefundStatus.REFUNDED.getCode(), f.refundOf(71L).getStatus());
        assertEquals(GzJpOrderStatus.REFUNDED.getCode(), f.orderStatus(7L));

        // 迟到的失败回调（第一次提交产生的）—— 已终态，不翻转
        assertTrue(f.service.handleRefundNotify(ctx(f.callbackBody(refund, "CLOSED"))));
        assertEquals(GzJpRefundStatus.REFUNDED.getCode(), f.refundOf(71L).getStatus(),
            "已退款是终态，迟到的 CLOSED 不能把它打回失败");
        assertEquals(10000L, f.row(71L).getRefundAmountCent());
        System.out.println("[重试×迟到回调] 重试成功后迟到的 CLOSED 被当重复回调丢弃，已退金额保持 10000");
    }

    @Test
    @DisplayName("已退款的单不允许重试（钱已经退了，重试等于退第二次）")
    void refundedCannotRetry() {
        GzJpRefundFixture f = new GzJpRefundFixture();
        GzJpRefund refund = arrange(f, 8L, 81L, 10000L);
        f.service.handleRefundNotify(ctx(f.callbackBody(refund, "SUCCESS")));

        org.dromara.common.core.exception.ServiceException e =
            assertThrows(org.dromara.common.core.exception.ServiceException.class,
                () -> f.service.retry(refund.getId(), "admin"));
        assertEquals(org.dromara.gz.jp.exception.GzJpRefundErrorCode.REFUND_RETRY_NOT_ALLOWED, e.getCode());
        assertEquals(1, f.refundOf(81L).getAttemptCount(), "★ 提交次数没有增加 —— 根本没再调微信");
        System.out.println("[不可重试] 已退款单点重试 → 4115 " + e.getMessage());
    }

    @Test
    @DisplayName("刚发起、还在等回调的单不允许重试（避免店员狂点）")
    void freshRefundingCannotRetry() {
        GzJpRefundFixture f = new GzJpRefundFixture();
        GzJpRefund refund = arrange(f, 9L, 91L, 10000L);

        org.dromara.common.core.exception.ServiceException e =
            assertThrows(org.dromara.common.core.exception.ServiceException.class,
                () -> f.service.retry(refund.getId(), "admin"));
        assertEquals(org.dromara.gz.jp.exception.GzJpRefundErrorCode.REFUND_RETRY_NOT_ALLOWED, e.getCode());
        assertEquals(1, f.refundOf(91L).getAttemptCount());

        // 超过静默期（模拟 11 分钟前发起）→ 放行，覆盖"事务提交后进程挂了"的崩溃窗口
        f.refundOf(91L).setTriggeredTime(java.time.LocalDateTime.now().minusMinutes(11));
        f.service.retry(refund.getId(), "admin");
        assertEquals(2, f.refundOf(91L).getAttemptCount(), "重试真的又提交了一次微信");
        assertEquals(refund.getRefundNo(), f.refundOf(91L).getRefundNo(), "★ 复用同一个 out_refund_no");
        System.out.println("[静默期] 刚发起点重试 → 4115；超过 10 分钟无回调 → 放行且复用同号 "
            + refund.getRefundNo());
    }
}
