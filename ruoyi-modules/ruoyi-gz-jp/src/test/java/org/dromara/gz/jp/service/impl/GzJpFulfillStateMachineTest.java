package org.dromara.gz.jp.service.impl;

import org.dromara.common.core.exception.ServiceException;
import org.dromara.gz.jp.domain.bo.GzJpFulfillAdvanceBo;
import org.dromara.gz.jp.domain.bo.GzJpFulfillShipBo;
import org.dromara.gz.jp.domain.enums.GzJpFulfillRejectReason;
import org.dromara.gz.jp.domain.enums.GzJpFulfillStatus;
import org.dromara.gz.jp.domain.vo.GzJpFulfillBatchResultVO;
import org.dromara.gz.jp.exception.GzJpFulfillErrorCode;
import org.dromara.gz.jp.service.internal.GzJpFulfillStateMachine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 履约状态机单测（GZ-JP-106，FLOW:F-JP-03.step2）。
 *
 * <p><b>accept 第 1 条直接跑本类</b>（「非法状态转移被拒 + 合法链路全程可达」）。</p>
 *
 * <p>两层都测：</p>
 * <ul>
 *   <li><b>纯判定层</b>（{@link GzJpFulfillStateMachine}）—— 7×7 全矩阵逐格断言，
 *       不给「某个组合没想到」留缝</li>
 *   <li><b>服务层</b>（{@link GzJpFulfillServiceImpl}）—— 用内存版数据层
 *       {@link GzJpFulfillFixture}，<b>照搬 SQL 的 WHERE 守卫</b>，验真实批量语义：
 *       未支付订单的行被排除 / 部分成功 / 幂等跳过 / 去重升序防死锁</li>
 * </ul>
 *
 * @author kevin-coder (sensenran-guzi · GZ-JP-106)
 */
@Tag("dev")
@DisplayName("GZ-JP-106 履约状态机：非法转移被拒 + 合法链路全程可达")
class GzJpFulfillStateMachineTest {

    private static final String PURCHASING = GzJpFulfillStatus.PURCHASING.getCode();
    private static final String PURCHASE_FAILED = GzJpFulfillStatus.PURCHASE_FAILED.getCode();
    private static final String AWAIT_SELLER_SHIP = GzJpFulfillStatus.AWAIT_SELLER_SHIP.getCode();
    private static final String JP_SHIPPED = GzJpFulfillStatus.JP_SHIPPED.getCode();
    private static final String CUSTOMS = GzJpFulfillStatus.CUSTOMS.getCode();
    private static final String CN_SORTING = GzJpFulfillStatus.CN_SORTING.getCode();
    private static final String DELIVERED = GzJpFulfillStatus.DELIVERED.getCode();

    // ============================================================
    //  1. 纯判定层
    // ============================================================

    @Test
    @DisplayName("AC1 倒退一律被拒（含 customs → purchasing）")
    void backwardRejected() {
        // AC 点名的例子
        assertEquals(GzJpFulfillRejectReason.ILLEGAL_TARGET,
            GzJpFulfillStateMachine.checkAdvance(CUSTOMS, PURCHASING),
            "清关中 → 购买中 必须被拒");

        // 其余每一对「位次变小」的组合
        assertEquals(GzJpFulfillRejectReason.BACKWARD, GzJpFulfillStateMachine.checkAdvance(CN_SORTING, JP_SHIPPED));
        assertEquals(GzJpFulfillRejectReason.BACKWARD, GzJpFulfillStateMachine.checkAdvance(CUSTOMS, AWAIT_SELLER_SHIP));
        assertEquals(GzJpFulfillRejectReason.BACKWARD, GzJpFulfillStateMachine.checkAdvance(JP_SHIPPED, AWAIT_SELLER_SHIP));
        assertEquals(GzJpFulfillRejectReason.BACKWARD, GzJpFulfillStateMachine.checkAdvance(CN_SORTING, CUSTOMS));

        // 「购买中」是起点，永远不是推进目标（哪怕自己也是购买中）
        assertEquals(GzJpFulfillRejectReason.ILLEGAL_TARGET,
            GzJpFulfillStateMachine.checkAdvance(PURCHASING, PURCHASING));
        System.out.println("[AC1] 倒退被拒：customs→purchasing / cn_sorting→jp_shipped / customs→await_seller_ship 全部拒绝");
    }

    @Test
    @DisplayName("AC2 允许跳过中间态（现货没有「等待官方发货」）")
    void skipIntermediateAllowed() {
        assertNull(GzJpFulfillStateMachine.checkAdvance(PURCHASING, JP_SHIPPED), "购买中 → 日本仓库已发货 应允许");
        assertNull(GzJpFulfillStateMachine.checkAdvance(PURCHASING, CUSTOMS));
        assertNull(GzJpFulfillStateMachine.checkAdvance(PURCHASING, CN_SORTING));
        assertNull(GzJpFulfillStateMachine.checkAdvance(AWAIT_SELLER_SHIP, CN_SORTING));
        assertNull(GzJpFulfillStateMachine.checkShip(PURCHASING), "现货当天直接发货 应允许");
        System.out.println("[AC2] 跳过中间态允许：purchasing→jp_shipped / purchasing→customs / purchasing→delivered(发货)");
    }

    @Test
    @DisplayName("AC3 终态不可再动（delivered / purchase_failed 都锁死）")
    void terminalLocked() {
        for (String to : List.of(AWAIT_SELLER_SHIP, JP_SHIPPED, CUSTOMS, CN_SORTING, PURCHASE_FAILED)) {
            assertEquals(GzJpFulfillRejectReason.TERMINAL, GzJpFulfillStateMachine.checkAdvance(DELIVERED, to),
                "已发货完毕的行不该能推到 " + to);
            assertEquals(GzJpFulfillRejectReason.TERMINAL, GzJpFulfillStateMachine.checkAdvance(PURCHASE_FAILED, to),
                "购买失败的行不该能推到 " + to);
        }
        // 再发一次货换单号也不行
        assertEquals(GzJpFulfillRejectReason.TERMINAL, GzJpFulfillStateMachine.checkShip(DELIVERED));
        assertEquals(GzJpFulfillRejectReason.TERMINAL, GzJpFulfillStateMachine.checkShip(PURCHASE_FAILED));
        assertTrue(GzJpFulfillStateMachine.isTerminal(DELIVERED));
        assertTrue(GzJpFulfillStateMachine.isTerminal(PURCHASE_FAILED));
        System.out.println("[AC3] 终态锁死：delivered / purchase_failed 对任何目标（含再次发货）均返回 TERMINAL");
    }

    @Test
    @DisplayName("purchase_failed 是分支不是前进：任意非终态都能标失败")
    void purchaseFailedIsBranch() {
        for (String from : List.of(PURCHASING, AWAIT_SELLER_SHIP, JP_SHIPPED, CUSTOMS, CN_SORTING)) {
            assertNull(GzJpFulfillStateMachine.checkAdvance(from, PURCHASE_FAILED),
                from + " → purchase_failed 应允许（一期无回退，中途才发现买不到必须还能退款）");
        }
        // 位次相同不代表可以互相跳：回「购买中」先撞「起点不是推进目标」这道更前面的闸
        assertEquals(GzJpFulfillRejectReason.ILLEGAL_TARGET,
            GzJpFulfillStateMachine.checkAdvance(PURCHASE_FAILED, PURCHASING));
        // 换个非起点目标，撞的就是终态闸 —— 两条路都出不去
        assertEquals(GzJpFulfillRejectReason.TERMINAL,
            GzJpFulfillStateMachine.checkAdvance(PURCHASE_FAILED, AWAIT_SELLER_SHIP));
        System.out.println("[分支] purchase_failed 可从 5 个非终态进入，进入后不可再出");
    }

    @Test
    @DisplayName("delivered 不能由「批量推进」达成 —— 必须走批量发货填单号")
    void deliveredNeedsShip() {
        for (String from : List.of(PURCHASING, AWAIT_SELLER_SHIP, JP_SHIPPED, CUSTOMS, CN_SORTING)) {
            assertEquals(GzJpFulfillRejectReason.SHIP_REQUIRED, GzJpFulfillStateMachine.checkAdvance(from, DELIVERED),
                from + " → delivered 走推进端点应被要求改用发货端点");
        }
        System.out.println("[发货闸] 任何来源推进到 delivered 一律 SHIP_REQUIRED");
    }

    @Test
    @DisplayName("非法 / 未知取值：目标非法 → ILLEGAL_TARGET；当前值脏数据 → UNKNOWN_CURRENT")
    void illegalValues() {
        assertEquals(GzJpFulfillRejectReason.ILLEGAL_TARGET, GzJpFulfillStateMachine.checkAdvance(PURCHASING, "shipped"));
        assertEquals(GzJpFulfillRejectReason.ILLEGAL_TARGET, GzJpFulfillStateMachine.checkAdvance(PURCHASING, ""));
        assertEquals(GzJpFulfillRejectReason.ILLEGAL_TARGET, GzJpFulfillStateMachine.checkAdvance(PURCHASING, null));
        assertEquals(GzJpFulfillRejectReason.UNKNOWN_CURRENT, GzJpFulfillStateMachine.checkAdvance("garbage", CUSTOMS));
        assertEquals(GzJpFulfillRejectReason.UNKNOWN_CURRENT, GzJpFulfillStateMachine.checkShip(null));
    }

    @Test
    @DisplayName("★ 7×7 全矩阵：每一格的判定都被钉死（新增状态时这里会立刻炸）")
    void fullMatrix() {
        List<String> all = List.of(PURCHASING, PURCHASE_FAILED, AWAIT_SELLER_SHIP, JP_SHIPPED, CUSTOMS, CN_SORTING, DELIVERED);
        int allowed = 0;
        int rejected = 0;
        StringBuilder sb = new StringBuilder();
        for (String from : all) {
            for (String to : all) {
                GzJpFulfillRejectReason r = GzJpFulfillStateMachine.checkAdvance(from, to);
                GzJpFulfillRejectReason expect = expectAdvance(from, to);
                assertEquals(expect, r, from + " → " + to);
                if (r == null) {
                    allowed++;
                    sb.append("  允许 ").append(from).append(" → ").append(to).append('\n');
                } else {
                    rejected++;
                }
            }
        }
        assertEquals(49, allowed + rejected);
        System.out.println("[矩阵] 7×7=49 组合：允许 " + allowed + " / 拒绝 " + rejected + "\n" + sb);
    }

    /** 期望值用「另一套写法」独立算一遍，避免和实现同源出错 */
    private GzJpFulfillRejectReason expectAdvance(String from, String to) {
        if (DELIVERED.equals(to)) {
            return GzJpFulfillRejectReason.SHIP_REQUIRED;
        }
        if (PURCHASING.equals(to)) {
            return GzJpFulfillRejectReason.ILLEGAL_TARGET;
        }
        if (DELIVERED.equals(from) || PURCHASE_FAILED.equals(from)) {
            return GzJpFulfillRejectReason.TERMINAL;
        }
        if (from.equals(to)) {
            // 已是目标态：合法，调用方按幂等跳过处理（不是倒退）
            return null;
        }
        if (PURCHASE_FAILED.equals(to)) {
            return null;
        }
        int f = GzJpFulfillStatus.valueOf(nameOf(from)).getStep();
        int t = GzJpFulfillStatus.valueOf(nameOf(to)).getStep();
        return t > f ? null : GzJpFulfillRejectReason.BACKWARD;
    }

    private String nameOf(String code) {
        for (GzJpFulfillStatus s : GzJpFulfillStatus.values()) {
            if (s.getCode().equals(code)) {
                return s.name();
            }
        }
        throw new IllegalArgumentException(code);
    }

    // ============================================================
    //  2. 服务层（内存数据层照搬 SQL 守卫）
    // ============================================================

    @Test
    @DisplayName("★ 合法链路全程可达：购买中 →…→ 发货完毕，逐步落库")
    void fullChainReachable() {
        GzJpFulfillFixture f = new GzJpFulfillFixture();
        f.paidOrder(1L, 100L).item(11L, 1L, PURCHASING);

        StringBuilder trail = new StringBuilder(f.statusOf(11L));
        for (String next : List.of(AWAIT_SELLER_SHIP, JP_SHIPPED, CUSTOMS, CN_SORTING)) {
            GzJpFulfillBatchResultVO r = f.service.advance(advanceBo(next, 11L), 1L);
            assertEquals(1, r.getAdvanced());
            assertEquals(next, f.statusOf(11L));
            trail.append(" → ").append(next);
        }
        // 最后一步只能走发货
        GzJpFulfillBatchResultVO shipped = f.service.ship(shipBo("sf", "SF1234567890", 11L), 1L);
        assertEquals(1, shipped.getAdvanced());
        assertEquals(DELIVERED, f.statusOf(11L));
        assertEquals("SF1234567890", f.row(11L).getTrackingNo());
        assertEquals("sf", f.row(11L).getCarrierCode());
        assertNotNull(f.row(11L).getShippedAt());
        trail.append(" → ").append(DELIVERED);
        System.out.println("[链路] " + trail + "（版本号 " + f.row(11L).getVersion() + "，每步 +1）");
        assertEquals(6, f.row(11L).getVersion(), "初始 1 + 5 次改动");
    }

    @Test
    @DisplayName("★ 跳过中间态：购买中直接推到日本仓库已发货（现货）")
    void serviceSkipIntermediate() {
        GzJpFulfillFixture f = new GzJpFulfillFixture();
        f.paidOrder(1L, 100L).item(11L, 1L, PURCHASING).item(12L, 1L, PURCHASING);

        GzJpFulfillBatchResultVO r = f.service.advance(advanceBo(JP_SHIPPED, 11L, 12L), 1L);
        assertEquals(2, r.getAdvanced());
        assertEquals(0, r.getRejected());
        assertEquals(JP_SHIPPED, f.statusOf(11L));
        assertEquals(JP_SHIPPED, f.statusOf(12L));
        System.out.println("[跳过] purchasing ×2 一次推到 jp_shipped，advanced=" + r.getAdvanced());
    }

    @Test
    @DisplayName("★★ 未支付订单的行必须被排除（它的 fulfill_status 也是 purchasing）")
    void unpaidOrderRowsExcluded() {
        GzJpFulfillFixture f = new GzJpFulfillFixture();
        f.paidOrder(1L, 100L).item(11L, 1L, PURCHASING);
        f.unpaidOrder(2L, 100L).item(21L, 2L, PURCHASING);

        GzJpFulfillBatchResultVO r = f.service.advance(advanceBo(JP_SHIPPED, 11L, 21L), 1L);

        assertEquals(1, r.getAdvanced());
        assertEquals(1, r.getRejected());
        assertEquals(GzJpFulfillRejectReason.ORDER_UNPAID.name(), r.getRejects().get(0).getReasonCode());
        assertEquals(21L, r.getRejects().get(0).getItemId());
        assertEquals(JP_SHIPPED, f.statusOf(11L), "已付款的行照常推进");
        assertEquals(PURCHASING, f.statusOf(21L), "未付款订单的行必须原封不动");
        System.out.println("[未支付] 混入未支付订单行 21：advanced=1 rejected=1 reason="
            + r.getRejects().get(0).getReason() + "；该行状态仍为 " + f.statusOf(21L));
    }

    @Test
    @DisplayName("整批全是未支付行 → 4108，一行不写")
    void allUnpaidThrows() {
        GzJpFulfillFixture f = new GzJpFulfillFixture();
        f.unpaidOrder(2L, 100L).item(21L, 2L, PURCHASING).item(22L, 2L, PURCHASING);

        ServiceException e = assertThrows(ServiceException.class,
            () -> f.service.advance(advanceBo(JP_SHIPPED, 21L, 22L), 1L));
        assertEquals(GzJpFulfillErrorCode.NOTHING_ADVANCED, e.getCode());
        assertEquals(PURCHASING, f.statusOf(21L));
        assertEquals(PURCHASING, f.statusOf(22L));
        System.out.println("[4108] " + e.getMessage());
    }

    @Test
    @DisplayName("★ 跨越终态被拒：purchase_failed / delivered 的行不被任何批量操作改动")
    void terminalRowsUntouched() {
        GzJpFulfillFixture f = new GzJpFulfillFixture();
        f.paidOrder(1L, 100L)
            .item(11L, 1L, PURCHASING)
            .item(12L, 1L, PURCHASE_FAILED)
            .item(13L, 1L, DELIVERED);
        f.row(13L).setTrackingNo("SF0000000001");
        f.row(13L).setCarrierCode("sf");

        GzJpFulfillBatchResultVO r = f.service.advance(advanceBo(CUSTOMS, 11L, 12L, 13L), 1L);
        assertEquals(1, r.getAdvanced());
        assertEquals(2, r.getRejected());
        for (var rej : r.getRejects()) {
            assertEquals(GzJpFulfillRejectReason.TERMINAL.name(), rej.getReasonCode());
        }
        assertEquals(PURCHASE_FAILED, f.statusOf(12L));
        assertEquals(DELIVERED, f.statusOf(13L));
        assertEquals("SF0000000001", f.row(13L).getTrackingNo(), "已发货行的单号不能被后续批量覆盖");

        // 发货端点同样不碰终态行
        GzJpFulfillBatchResultVO shipped = f.service.ship(shipBo("sf", "SF9999999999", 11L, 12L, 13L), 1L);
        assertEquals(1, shipped.getAdvanced());
        assertEquals(2, shipped.getRejected());
        assertEquals("SF0000000001", f.row(13L).getTrackingNo());
        System.out.println("[终态] 推进与发货两条路径下，purchase_failed / delivered 行均被拒且数据零变化");
    }

    @Test
    @DisplayName("已是目标态 = 幂等跳过，不算失败也不重复写版本号")
    void sameStatusSkipped() {
        GzJpFulfillFixture f = new GzJpFulfillFixture();
        f.paidOrder(1L, 100L).item(11L, 1L, CUSTOMS).item(12L, 1L, JP_SHIPPED);
        int v11 = f.row(11L).getVersion();

        GzJpFulfillBatchResultVO r = f.service.advance(advanceBo(CUSTOMS, 11L, 12L), 1L);
        assertEquals(1, r.getAdvanced());
        assertEquals(1, r.getSkipped());
        assertEquals(0, r.getRejected());
        assertEquals(v11, f.row(11L).getVersion(), "跳过的行不该被写");

        // 全部已是目标态 → 200 幂等成功，不抛 4108
        GzJpFulfillBatchResultVO again = f.service.advance(advanceBo(CUSTOMS, 11L, 12L), 1L);
        assertEquals(0, again.getAdvanced());
        assertEquals(2, again.getSkipped());
        System.out.println("[幂等] 重复推进同一批：advanced=0 skipped=2，不报错不写库");
    }

    @Test
    @DisplayName("行不存在 → 逐行 NOT_FOUND，其余照常推进")
    void missingRowRejected() {
        GzJpFulfillFixture f = new GzJpFulfillFixture();
        f.paidOrder(1L, 100L).item(11L, 1L, PURCHASING);

        GzJpFulfillBatchResultVO r = f.service.advance(advanceBo(CUSTOMS, 11L, 999L), 1L);
        assertEquals(1, r.getAdvanced());
        assertEquals(1, r.getRejected());
        assertEquals(GzJpFulfillRejectReason.NOT_FOUND.name(), r.getRejects().get(0).getReasonCode());
    }

    @Test
    @DisplayName("★ 防死锁：加锁前 id 必去重 + 升序（乱序入参也一样）")
    void idsDedupedAndSorted() {
        GzJpFulfillFixture f = new GzJpFulfillFixture();
        f.paidOrder(1L, 100L);
        for (long id : new long[]{11L, 12L, 13L}) {
            f.item(id, 1L, PURCHASING);
        }
        GzJpFulfillAdvanceBo bo = new GzJpFulfillAdvanceBo();
        bo.setItemIds(new ArrayList<>(Arrays.asList(13L, 11L, 12L, 11L, null, 13L)));
        bo.setTargetStatus(CUSTOMS);

        GzJpFulfillBatchResultVO r = f.service.advance(bo, 1L);

        assertEquals(1, f.lockCalls.size());
        assertEquals(List.of(11L, 12L, 13L), f.lockCalls.get(0), "必须去重且升序，否则并发重叠请求会死锁");
        assertEquals(3, r.getRequested());
        assertEquals(3, r.getAdvanced());
        System.out.println("[防死锁] 入参 [13,11,12,11,null,13] → 实际加锁顺序 " + f.lockCalls.get(0));
    }

    @Test
    @DisplayName("单次上限 200 行；空选择直接拒")
    void batchLimits() {
        GzJpFulfillFixture f = new GzJpFulfillFixture();
        GzJpFulfillAdvanceBo tooMany = new GzJpFulfillAdvanceBo();
        List<Long> ids = new ArrayList<>();
        for (long i = 1; i <= 201; i++) {
            ids.add(i);
        }
        tooMany.setItemIds(ids);
        tooMany.setTargetStatus(CUSTOMS);
        ServiceException e = assertThrows(ServiceException.class, () -> f.service.advance(tooMany, 1L));
        assertTrue(e.getMessage().contains("200"));

        GzJpFulfillAdvanceBo empty = new GzJpFulfillAdvanceBo();
        empty.setItemIds(List.of());
        empty.setTargetStatus(CUSTOMS);
        assertThrows(ServiceException.class, () -> f.service.advance(empty, 1L));
        System.out.println("[上限] 201 行被拒：" + e.getMessage());
    }

    @Test
    @DisplayName("目标状态非法 → 直接拒，不读库不写库")
    void illegalTargetThrowsBeforeAnyRead() {
        GzJpFulfillFixture f = new GzJpFulfillFixture();
        f.paidOrder(1L, 100L).item(11L, 1L, PURCHASING);

        assertThrows(ServiceException.class, () -> f.service.advance(advanceBo("shipped", 11L), 1L));
        assertEquals(0, f.lockCalls.size(), "参数就非法时不该去锁任何行");
        assertEquals(PURCHASING, f.statusOf(11L));
    }

    @Test
    @DisplayName("一批跨多订单 / 多状态：按当前状态分组写，各自到达同一目标")
    void mixedStatusesGrouped() {
        GzJpFulfillFixture f = new GzJpFulfillFixture();
        f.paidOrder(1L, 100L).item(11L, 1L, PURCHASING).item(12L, 1L, AWAIT_SELLER_SHIP);
        f.paidOrder(2L, 100L).item(21L, 2L, JP_SHIPPED);

        GzJpFulfillBatchResultVO r = f.service.advance(advanceBo(CN_SORTING, 11L, 12L, 21L), 1L);
        assertEquals(3, r.getAdvanced());
        assertEquals(CN_SORTING, f.statusOf(11L));
        assertEquals(CN_SORTING, f.statusOf(12L));
        assertEquals(CN_SORTING, f.statusOf(21L));
        System.out.println("[分组] 三种起始状态（purchasing/await_seller_ship/jp_shipped）一次推到 cn_sorting");
    }

    // ============================================================

    private GzJpFulfillAdvanceBo advanceBo(String target, Long... ids) {
        GzJpFulfillAdvanceBo bo = new GzJpFulfillAdvanceBo();
        bo.setItemIds(Arrays.asList(ids));
        bo.setTargetStatus(target);
        return bo;
    }

    private GzJpFulfillShipBo shipBo(String carrier, String trackingNo, Long... ids) {
        GzJpFulfillShipBo bo = new GzJpFulfillShipBo();
        bo.setItemIds(Arrays.asList(ids));
        bo.setCarrierCode(carrier);
        bo.setTrackingNo(trackingNo);
        return bo;
    }
}
