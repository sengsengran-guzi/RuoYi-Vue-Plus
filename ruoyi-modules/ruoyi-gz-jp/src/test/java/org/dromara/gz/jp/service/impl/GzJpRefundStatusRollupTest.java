package org.dromara.gz.jp.service.impl;

import org.dromara.common.core.exception.ServiceException;
import org.dromara.gz.jp.domain.bo.GzJpMarkFailedBo;
import org.dromara.gz.jp.domain.entity.GzJpRefund;
import org.dromara.gz.jp.domain.enums.GzJpFulfillRejectReason;
import org.dromara.gz.jp.domain.enums.GzJpOrderStatus;
import org.dromara.gz.jp.domain.enums.GzJpRefundSkipReason;
import org.dromara.gz.jp.domain.enums.GzJpRefundStatus;
import org.dromara.gz.jp.domain.vo.GzJpMarkFailedResultVO;
import org.dromara.gz.jp.exception.GzJpRefundErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 行级退款 + 订单状态 rollup 单测（GZ-JP-107，FLOW:F-JP-04.step2/step3）。
 *
 * <p><b>accept 第 2 条直接跑本类</b>（「部分行失败时订单转 partial_refunded，全失败才 refunded」）。</p>
 *
 * <p>顺带把 accept 第 1 条（「退款金额等于该行金额，不是整单」）在<b>代码层</b>也钉死 ——
 * accept 第 1 条本身是 DATA 断言（扫全表），但那只能证明"库里现在没有坏数据"；
 * 这里断言的是"代码不可能造出坏数据"：<b>提交给微信的报文里退款额 = 行金额、原单总额 = 支付流水金额</b>。</p>
 *
 * <p>数据层用 {@link GzJpRefundFixture}（<b>逐字照搬 SQL 守卫</b>，含唯一键），
 * 微信通道用真的 {@code MockWechatPayClient}。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-JP-107)
 */
@Tag("dev")
@DisplayName("GZ-JP-107 行级退款：金额按行不按单 + 订单 rollup（部分/全部）")
class GzJpRefundStatusRollupTest {

    private static GzJpMarkFailedBo bo(String reason, Long... ids) {
        GzJpMarkFailedBo b = new GzJpMarkFailedBo();
        b.setItemIds(List.of(ids));
        b.setReason(reason);
        return b;
    }

    /** 走完整链路：标失败 → 发起退款 → 模拟微信回调 SUCCESS */
    private static void callbackSuccess(GzJpRefundFixture f, GzJpRefund refund) {
        f.service.handleRefundNotify(new org.dromara.gz.common.pay.service.internal
            .IWechatPayClient.NotifyContext("ts", "nonce", "sig", "serial",
            f.callbackBody(refund, "SUCCESS")));
    }

    // ============================================================
    //  accept[2] 主场景：订单 rollup
    // ============================================================

    @Test
    @DisplayName("accept[2] 一单 3 行只失败 1 行 → 退款回调后订单 partial_refunded（另外 2 行照常发货）")
    void partialRefundRollup() {
        GzJpRefundFixture f = new GzJpRefundFixture();
        f.paidOrder(1L, 14L, 30000L)
            .item(11L, 1L, 10000L, GzJpRefundFixture.PURCHASING)
            .item(12L, 1L, 12000L, GzJpRefundFixture.PURCHASING)
            .item(13L, 1L, 8000L, GzJpRefundFixture.PURCHASING);

        GzJpMarkFailedResultVO r = f.service.markPurchaseFailedAndRefund(bo("日方缺货", 12L), 1L, "admin");

        assertEquals(1, r.getMarkedFailed());
        assertEquals(1, r.getRefundsCreated());
        assertEquals(1, r.getRefundsAccepted());
        assertEquals(0, r.getRefundsFailed());
        // ★ 退的是 12000（该行），不是 30000（整单）
        assertEquals(12000L, r.getRefundAmountCentTotal());

        GzJpRefund refund = f.refundOf(12L);
        assertNotNull(refund);
        assertEquals(12000L, refund.getRefundAmountCent(), "退款额必须 = 该行 amount_cent");
        assertEquals(30000L, refund.getTotalAmountCent(), "原单总额必须 = 支付流水金额（微信校验用）");
        assertEquals(GzJpRefundStatus.REFUNDING.getCode(), refund.getStatus());

        // 受理成功后还没到账 → 订单仍是 paid（★ business_status 是"钱"的状态）
        assertEquals(GzJpOrderStatus.PAID.getCode(), f.orderStatus(1L),
            "退款还在路上时订单不该显示部分退款——钱没回到客人手里");

        callbackSuccess(f, refund);

        assertEquals(GzJpRefundStatus.REFUNDED.getCode(), f.refundOf(12L).getStatus());
        assertEquals(GzJpRefundStatus.REFUNDED.getCode(), f.row(12L).getRefundStatus());
        assertEquals(12000L, f.row(12L).getRefundAmountCent(), "★ accept[1]：行上已退金额 = 该行金额");
        assertEquals(GzJpOrderStatus.PARTIAL_REFUNDED.getCode(), f.orderStatus(1L));

        // 另外两行完全没被碰过 —— 照常发货（106 交代：rollup 后剩余行仍能推进）
        assertEquals(GzJpRefundFixture.PURCHASING, f.row(11L).getFulfillStatus());
        assertNull(f.row(11L).getRefundStatus());
        assertEquals(GzJpRefundFixture.PURCHASING, f.row(13L).getFulfillStatus());
        assertNull(f.row(13L).getRefundStatus());

        System.out.println("[accept2 部分] 3 行退 1 行：退款额 12000（行）≠ 30000（单）；订单 paid → partial_refunded；"
            + "另 2 行零变化");
    }

    @Test
    @DisplayName("accept[2] 全部 3 行都失败 → 全部退款回调后订单 refunded（中途一直是 partial_refunded）")
    void fullRefundRollup() {
        GzJpRefundFixture f = new GzJpRefundFixture();
        f.paidOrder(2L, 14L, 30000L)
            .item(21L, 2L, 10000L, GzJpRefundFixture.PURCHASING)
            .item(22L, 2L, 12000L, GzJpRefundFixture.PURCHASING)
            .item(23L, 2L, 8000L, GzJpRefundFixture.CUSTOMS);

        GzJpMarkFailedResultVO r = f.service.markPurchaseFailedAndRefund(bo(null, 21L, 22L, 23L), 1L, "admin");
        assertEquals(3, r.getMarkedFailed(), "purchase_failed 是分支，清关中的行也能标失败");
        assertEquals(3, r.getRefundsAccepted());
        assertEquals(30000L, r.getRefundAmountCentTotal());

        // 逐笔回调 —— 中途每一笔都只让订单停在 partial_refunded
        callbackSuccess(f, f.refundOf(21L));
        assertEquals(GzJpOrderStatus.PARTIAL_REFUNDED.getCode(), f.orderStatus(2L), "1/3 退完 → 部分退款");
        callbackSuccess(f, f.refundOf(22L));
        assertEquals(GzJpOrderStatus.PARTIAL_REFUNDED.getCode(), f.orderStatus(2L), "2/3 退完 → 仍是部分退款");
        callbackSuccess(f, f.refundOf(23L));
        assertEquals(GzJpOrderStatus.REFUNDED.getCode(), f.orderStatus(2L), "3/3 退完 → 已退款");

        // 每一行的已退金额都等于自己的行金额（accept[1] 的代码层保证）
        assertEquals(10000L, f.row(21L).getRefundAmountCent());
        assertEquals(12000L, f.row(22L).getRefundAmountCent());
        assertEquals(8000L, f.row(23L).getRefundAmountCent());
        System.out.println("[accept2 全部] 3 行全失败：1/3→partial 2/3→partial 3/3→refunded；"
            + "逐行金额 10000/12000/8000 各归各");
    }

    @Test
    @DisplayName("accept[2] 退款还没成功时不 rollup —— 标了失败但受理被拒，订单仍是 paid")
    void noRollupWhenRefundNotSucceeded() {
        GzJpRefundFixture f = new GzJpRefundFixture();
        f.paidOrder(3L, 14L, 10000L).item(31L, 3L, 10000L, GzJpRefundFixture.PURCHASING);
        f.payClient.setRefundAcceptFail(true);

        GzJpMarkFailedResultVO r = f.service.markPurchaseFailedAndRefund(bo(null, 31L), 1L, "admin");

        assertEquals(1, r.getMarkedFailed(), "履约侧标上了");
        assertEquals(0, r.getRefundsAccepted());
        assertEquals(1, r.getRefundsFailed(), "★ 退款侧失败必须单独计数，不能被履约侧的成功盖住");

        // ★ AC：退款失败有明确落库状态 + 原因，不静默吞
        assertEquals(GzJpRefundStatus.REFUND_FAILED.getCode(), f.refundOf(31L).getStatus());
        assertTrue(f.refundOf(31L).getFailReason().contains("模拟退款受理失败"),
            "失败原因必须落库，admin 才看得到");
        assertEquals(GzJpRefundStatus.REFUND_FAILED.getCode(), f.row(31L).getRefundStatus());
        assertNull(f.row(31L).getRefundAmountCent(), "没退成功就不该有已退金额");

        // ★ 刻意不回滚 purchase_failed：货确实没买到，是既成的履约事实
        assertEquals(GzJpRefundFixture.PURCHASE_FAILED, f.row(31L).getFulfillStatus());
        // ★ 钱没退出去 → 订单不该显示"已退款"
        assertEquals(GzJpOrderStatus.PAID.getCode(), f.orderStatus(3L));
        System.out.println("[accept2 未成功不 rollup] 受理被拒：refund_failed + 原因落库；"
            + "行仍 purchase_failed（不回滚事实）；订单仍 paid");
    }

    @Test
    @DisplayName("accept[2] 失败重试成功后订单自愈：partial_refunded → refunded")
    void rollupSelfHealsAfterRetry() {
        GzJpRefundFixture f = new GzJpRefundFixture();
        f.paidOrder(4L, 14L, 20000L)
            .item(41L, 4L, 10000L, GzJpRefundFixture.PURCHASING)
            .item(42L, 4L, 10000L, GzJpRefundFixture.PURCHASING);

        // 第一笔成功
        f.service.markPurchaseFailedAndRefund(bo(null, 41L), 1L, "admin");
        callbackSuccess(f, f.refundOf(41L));
        assertEquals(GzJpOrderStatus.PARTIAL_REFUNDED.getCode(), f.orderStatus(4L));

        // 第二笔受理失败
        f.payClient.setRefundAcceptFail(true);
        f.service.markPurchaseFailedAndRefund(bo(null, 42L), 1L, "admin");
        assertEquals(GzJpRefundStatus.REFUND_FAILED.getCode(), f.refundOf(42L).getStatus());
        assertEquals(GzJpOrderStatus.PARTIAL_REFUNDED.getCode(), f.orderStatus(4L),
            "一笔卡住 → 订单停在部分退款（准确：确实只退了一半）");

        // admin 重试 → 成功 → 回调 → 订单自愈到 refunded
        f.payClient.setRefundAcceptFail(false);
        Long refundId = f.refundOf(42L).getId();
        f.service.retry(refundId, "admin");
        assertEquals(GzJpRefundStatus.REFUNDING.getCode(), f.refundOf(42L).getStatus());
        // ★ 重试复用同一个 refund_no（微信按 out_refund_no 幂等）
        assertEquals("JPRF-20260807-000002", f.refundOf(42L).getRefundNo());
        callbackSuccess(f, f.refundOf(42L));
        assertEquals(GzJpOrderStatus.REFUNDED.getCode(), f.orderStatus(4L));
        System.out.println("[accept2 自愈] 一笔失败停在 partial_refunded → admin 重试（复用同号）→ 回调 → refunded");
    }

    // ============================================================
    //  金额与"退整单"的防线
    // ============================================================

    @Test
    @DisplayName("accept[1] 一单多行分批标失败：每笔退款各退各的行金额，绝不出现整单额")
    void refundAmountIsPerLineNeverOrderTotal() {
        GzJpRefundFixture f = new GzJpRefundFixture();
        f.paidOrder(5L, 14L, 51200L)
            .item(51L, 5L, 25600L, GzJpRefundFixture.PURCHASING)
            .item(52L, 5L, 20400L, GzJpRefundFixture.PURCHASING)
            .item(53L, 5L, 5200L, GzJpRefundFixture.PURCHASING);

        f.service.markPurchaseFailedAndRefund(bo(null, 51L), 1L, "admin");
        f.service.markPurchaseFailedAndRefund(bo(null, 52L, 53L), 1L, "admin");

        assertEquals(25600L, f.refundOf(51L).getRefundAmountCent());
        assertEquals(20400L, f.refundOf(52L).getRefundAmountCent());
        assertEquals(5200L, f.refundOf(53L).getRefundAmountCent());
        // 三笔加起来才等于整单 —— 任何一笔都不等于整单
        assertEquals(51200L, f.refunds.values().stream().mapToLong(GzJpRefund::getRefundAmountCent).sum());
        for (GzJpRefund r : f.refunds.values()) {
            assertEquals(51200L, r.getTotalAmountCent(), "原单总额三笔都一样（微信校验用），与退款额是两个参数");
        }
        System.out.println("[accept1] 分两批退 3 行：25600 / 20400 / 5200，Σ=51200=整单；单笔均 ≠ 整单");
    }

    @Test
    @DisplayName("超退兜底：已退总额 + 本次 > 原单总额 → 拒绝提交微信（4113）")
    void refuseWhenExceedsOrderTotal() {
        GzJpRefundFixture f = new GzJpRefundFixture();
        // 刻意造脏数据：行金额之和 > 订单总额（正常不可能，这里就是要打这道兜底闸）
        f.paidOrder(6L, 14L, 10000L)
            .item(61L, 6L, 8000L, GzJpRefundFixture.PURCHASING)
            .item(62L, 6L, 8000L, GzJpRefundFixture.PURCHASING);

        GzJpMarkFailedResultVO r = f.service.markPurchaseFailedAndRefund(bo(null, 61L, 62L), 1L, "admin");

        assertEquals(2, r.getMarkedFailed());
        assertEquals(1, r.getRefundsCreated(), "只有第一行建了退款单");
        assertEquals(8000L, r.getRefundAmountCentTotal());
        assertNotNull(f.refundOf(61L));
        assertNull(f.refundOf(62L), "第二行会超出原单总额 → 一分钱都不提交");
        assertTrue(r.getRefunds().stream().anyMatch(
            l -> String.valueOf(GzJpRefundErrorCode.REFUND_EXCEEDS_TOTAL).equals(l.getSkipReasonCode())));
        System.out.println("[超退兜底] 原单 10000、两行各 8000：第 1 行退 8000，第 2 行被 4113 拒（不提交微信）");
    }

    @Test
    @DisplayName("缺原支付流水 → 该行拒绝退款（4112），不凭空造一笔")
    void refuseWhenPayTransactionMissing() {
        GzJpRefundFixture f = new GzJpRefundFixture();
        f.order(7L, 14L, 10000L, GzJpOrderStatus.PAID.getCode(), false)  // paid 但没有支付流水（手工改过的脏数据）
            .item(71L, 7L, 10000L, GzJpRefundFixture.PURCHASING);

        GzJpMarkFailedResultVO r = f.service.markPurchaseFailedAndRefund(bo(null, 71L), 1L, "admin");
        assertEquals(1, r.getMarkedFailed());
        assertEquals(0, r.getRefundsCreated());
        assertNull(f.refundOf(71L));
        assertTrue(r.getRefunds().stream().anyMatch(
            l -> String.valueOf(GzJpRefundErrorCode.PAY_TXN_MISSING).equals(l.getSkipReasonCode())));
        assertEquals(GzJpOrderStatus.PAID.getCode(), f.orderStatus(7L));
        System.out.println("[缺流水] paid 但 pay_transaction_id 为空：状态标上、退款被 4112 拒，不凭空造退款单");
    }

    // ============================================================
    //  履约侧的闸（复用 106 的状态机，本类只验它确实被用上了）
    // ============================================================

    @Test
    @DisplayName("未支付订单的行一分钱都不退（★ 它的 fulfill_status 也是 purchasing）")
    void unpaidOrderRowsNeverRefunded() {
        GzJpRefundFixture f = new GzJpRefundFixture();
        f.paidOrder(8L, 14L, 10000L).item(81L, 8L, 10000L, GzJpRefundFixture.PURCHASING);
        f.unpaidOrder(9L, 14L, 5000L).item(91L, 9L, 5000L, GzJpRefundFixture.PURCHASING);

        GzJpMarkFailedResultVO r = f.service.markPurchaseFailedAndRefund(bo(null, 81L, 91L), 1L, "admin");

        assertEquals(1, r.getMarkedFailed());
        assertEquals(1, r.getRejected());
        assertEquals(GzJpFulfillRejectReason.ORDER_UNPAID.name(), r.getRejects().get(0).getReasonCode());
        assertNull(f.refundOf(91L), "未支付订单的行不能有退款单");
        assertEquals(GzJpRefundFixture.PURCHASING, f.row(91L).getFulfillStatus(), "该行零变化");
        assertEquals(10000L, r.getRefundAmountCentTotal());
        System.out.println("[未支付] 混入未支付行 91：只退 81 的 10000，91 被 ORDER_UNPAID 拒且零变化");
    }

    @Test
    @DisplayName("已发货完毕（终态）的行不能标失败 —— 客人已经收到货了")
    void deliveredRowCannotBeMarkedFailed() {
        GzJpRefundFixture f = new GzJpRefundFixture();
        f.paidOrder(10L, 14L, 10000L).item(101L, 10L, 10000L, GzJpRefundFixture.DELIVERED);

        ServiceException e = assertThrows(ServiceException.class,
            () -> f.service.markPurchaseFailedAndRefund(bo(null, 101L), 1L, "admin"));
        assertEquals(GzJpRefundErrorCode.NOTHING_MARKED, e.getCode());
        assertNull(f.refundOf(101L));
        assertEquals(GzJpRefundFixture.DELIVERED, f.row(101L).getFulfillStatus());
        System.out.println("[终态] delivered 行标失败 → 4111 " + e.getMessage());
    }

    // ============================================================
    //  重复退款防线（DB 唯一键 + 人话原因）
    // ============================================================

    @Test
    @DisplayName("★ 同一行重复标失败绝不退第二次（refunding 中 → 跳过并说明原因）")
    void neverRefundSameLineTwice() {
        GzJpRefundFixture f = new GzJpRefundFixture();
        f.paidOrder(11L, 14L, 10000L).item(111L, 11L, 10000L, GzJpRefundFixture.PURCHASING);

        GzJpMarkFailedResultVO first = f.service.markPurchaseFailedAndRefund(bo(null, 111L), 1L, "admin");
        assertEquals(1, first.getRefundsCreated());

        GzJpMarkFailedResultVO second = f.service.markPurchaseFailedAndRefund(bo(null, 111L), 1L, "admin");
        assertEquals(0, second.getMarkedFailed());
        assertEquals(1, second.getAlreadyFailed());
        assertEquals(0, second.getRefundsCreated(), "★ 第二次一分钱都不能再退");
        assertEquals(1, second.getRefundsSkipped());
        assertEquals(GzJpRefundSkipReason.ALREADY_REFUNDING.name(),
            second.getRefunds().get(0).getSkipReasonCode());
        assertEquals(1, f.refunds.size(), "退款单表里始终只有一条");

        // 退成功之后再标一次，原因变成"已完成退款"
        callbackSuccess(f, f.refundOf(111L));
        GzJpMarkFailedResultVO third = f.service.markPurchaseFailedAndRefund(bo(null, 111L), 1L, "admin");
        assertEquals(0, third.getRefundsCreated());
        assertEquals(GzJpRefundSkipReason.ALREADY_REFUNDED.name(), third.getRefunds().get(0).getSkipReasonCode());
        assertEquals(1, f.refunds.size());
        System.out.println("[重复退款] 同一行标 3 次：只建 1 张退款单，第 2/3 次跳过（ALREADY_REFUNDING / ALREADY_REFUNDED）");
    }

    @Test
    @DisplayName("★ 补退款：被 106 的 /advance 标成 purchase_failed 但没退款的历史行会被补上")
    void healRowMarkedFailedWithoutRefund() {
        GzJpRefundFixture f = new GzJpRefundFixture();
        // 模拟 106 /advance 直接推成 purchase_failed（没有退款单）—— dev 库里的 item 24 就是这种
        f.paidOrder(12L, 14L, 25600L).item(121L, 12L, 25600L, GzJpRefundFixture.PURCHASE_FAILED);

        GzJpMarkFailedResultVO r = f.service.markPurchaseFailedAndRefund(bo(null, 121L), 1L, "admin");
        assertEquals(0, r.getMarkedFailed(), "状态本来就对，不用改");
        assertEquals(1, r.getAlreadyFailed());
        assertEquals(1, r.getRefundsCreated(), "★ 钱要补退，否则这行的钱永远出不去");
        assertEquals(25600L, r.getRefundAmountCentTotal());

        callbackSuccess(f, f.refundOf(121L));
        assertEquals(GzJpOrderStatus.REFUNDED.getCode(), f.orderStatus(12L), "整单只有这一行 → 全退");
        System.out.println("[补退款] 已是 purchase_failed 但无退款单的历史行：补建退款单并退 25600 → 订单 refunded");
    }

    @Test
    @DisplayName("上次退款失败的行不自动重发（要人看过原因再点重试）")
    void previouslyFailedNotAutoRetried() {
        GzJpRefundFixture f = new GzJpRefundFixture();
        f.paidOrder(13L, 14L, 10000L).item(131L, 13L, 10000L, GzJpRefundFixture.PURCHASING);
        f.payClient.setRefundAcceptFail(true);
        f.service.markPurchaseFailedAndRefund(bo(null, 131L), 1L, "admin");
        assertEquals(GzJpRefundStatus.REFUND_FAILED.getCode(), f.refundOf(131L).getStatus());

        f.payClient.setRefundAcceptFail(false);
        GzJpMarkFailedResultVO again = f.service.markPurchaseFailedAndRefund(bo(null, 131L), 1L, "admin");
        assertEquals(0, again.getRefundsCreated());
        assertEquals(GzJpRefundSkipReason.PREVIOUS_ATTEMPT_FAILED.name(),
            again.getRefunds().get(0).getSkipReasonCode());
        assertEquals(GzJpRefundStatus.REFUND_FAILED.getCode(), f.refundOf(131L).getStatus(), "状态没被悄悄改回去");
        System.out.println("[不自动重发] 上次失败的行再点标记：跳过并提示去退款单列表重新发起");
    }

    // ============================================================
    //  批量语义 / 防死锁
    // ============================================================

    @Test
    @DisplayName("批量入参去重 + 升序（防死锁）—— 排序发生在服务端，不依赖调用方守规矩")
    void idsNormalizedForDeadlockSafety() {
        GzJpRefundFixture f = new GzJpRefundFixture();
        f.paidOrder(14L, 14L, 30000L)
            .item(141L, 14L, 10000L, GzJpRefundFixture.PURCHASING)
            .item(142L, 14L, 10000L, GzJpRefundFixture.PURCHASING)
            .item(143L, 14L, 10000L, GzJpRefundFixture.PURCHASING);

        // 刻意降序 + 重复 + null
        GzJpMarkFailedBo b = new GzJpMarkFailedBo();
        b.setItemIds(java.util.Arrays.asList(143L, 141L, 142L, 141L, null, 143L));
        GzJpMarkFailedResultVO r = f.service.markPurchaseFailedAndRefund(b, 1L, "admin");

        assertEquals(3, r.getRequested(), "6 个入参去重去空后剩 3");
        assertEquals(List.of(141L, 142L, 143L), f.lockCalls.get(0), "★ 加锁顺序必须是升序");
        assertEquals(3, r.getRefundsAccepted());
        System.out.println("[防死锁] 入参 [143,141,142,141,null,143] → 实际加锁顺序 " + f.lockCalls.get(0));
    }

    @Test
    @DisplayName("跨订单批量：各单各自 rollup，互不影响")
    void crossOrderBatchRollupIndependently() {
        GzJpRefundFixture f = new GzJpRefundFixture();
        f.paidOrder(15L, 14L, 10000L).item(151L, 15L, 10000L, GzJpRefundFixture.PURCHASING);
        f.paidOrder(16L, 13L, 30000L)
            .item(161L, 16L, 10000L, GzJpRefundFixture.PURCHASING)
            .item(162L, 16L, 20000L, GzJpRefundFixture.PURCHASING);

        GzJpMarkFailedResultVO r = f.service.markPurchaseFailedAndRefund(bo(null, 151L, 161L), 1L, "admin");
        assertEquals(2, r.getRefundsAccepted());
        assertEquals(20000L, r.getRefundAmountCentTotal());

        callbackSuccess(f, f.refundOf(151L));
        callbackSuccess(f, f.refundOf(161L));
        assertEquals(GzJpOrderStatus.REFUNDED.getCode(), f.orderStatus(15L), "单 15 唯一一行退完 → refunded");
        assertEquals(GzJpOrderStatus.PARTIAL_REFUNDED.getCode(), f.orderStatus(16L), "单 16 两行退一行 → partial");
        assertNull(f.row(162L).getRefundStatus());
        System.out.println("[跨单] 一次标两单各一行：单15 → refunded、单16 → partial_refunded，互不影响");
    }

    @Test
    @DisplayName("行金额为 0：不调微信直接置已退款（微信不接受 0 元退款）")
    void zeroAmountLineSkipsChannel() {
        GzJpRefundFixture f = new GzJpRefundFixture();
        f.paidOrder(17L, 14L, 0L).item(171L, 17L, 0L, GzJpRefundFixture.PURCHASING);

        GzJpMarkFailedResultVO r = f.service.markPurchaseFailedAndRefund(bo(null, 171L), 1L, "admin");
        assertEquals(1, r.getRefundsCreated());
        assertEquals(0, r.getRefundsAccepted(), "没有走通道");
        assertEquals(0L, r.getRefundAmountCentTotal());
        assertEquals(GzJpRefundStatus.REFUNDED.getCode(), f.refundOf(171L).getStatus());
        assertEquals(0L, f.row(171L).getRefundAmountCent(), "★ accept[1]：0 == amount_cent，仍然相等");
        assertEquals(GzJpOrderStatus.REFUNDED.getCode(), f.orderStatus(17L), "零元行也要 rollup（没有回调会来做）");
        System.out.println("[零元行] 不调微信、直接 refunded，且在事务内完成 rollup");
    }

    @Test
    @DisplayName("GZ-PAY 全额退款旁路：订单转 refunded + 行补退款信息，但不碰 fulfill_status")
    void payDomainFullRefundSync() {
        GzJpRefundFixture f = new GzJpRefundFixture();
        f.paidOrder(18L, 14L, 30000L)
            .item(181L, 18L, 10000L, GzJpRefundFixture.DELIVERED)
            .item(182L, 18L, 20000L, GzJpRefundFixture.CUSTOMS);

        f.service.onFullRefundedByPayDomain("JPO-20260807-000018", "JPO-20260807-000019");

        assertEquals(GzJpOrderStatus.REFUNDED.getCode(), f.orderStatus(18L));
        assertEquals(10000L, f.row(181L).getRefundAmountCent());
        assertEquals(20000L, f.row(182L).getRefundAmountCent());
        // ★ 货走到哪是另一根轴（ADR-0007 双状态机正交）—— 全额退款不代表这些货没买到
        assertEquals(GzJpRefundFixture.DELIVERED, f.row(181L).getFulfillStatus());
        assertEquals(GzJpRefundFixture.CUSTOMS, f.row(182L).getFulfillStatus());
        System.out.println("[全额退款旁路] 订单 → refunded、两行补已退金额；fulfill_status 保持 delivered / customs 不变");
    }
}
