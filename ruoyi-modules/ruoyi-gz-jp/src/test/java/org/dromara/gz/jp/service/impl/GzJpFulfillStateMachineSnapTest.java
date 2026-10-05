package org.dromara.gz.jp.service.impl;

import org.dromara.gz.jp.domain.enums.GzJpFulfillRejectReason;
import org.dromara.gz.jp.domain.enums.GzJpFulfillStatus;
import org.dromara.gz.jp.domain.enums.GzJpItemSource;
import org.dromara.gz.jp.service.internal.GzJpFulfillStateMachine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GZ-SNAP-102 accept #0：按来源判定的状态机入口 {@code checkAdvance(source, from, to)}。
 *
 * <p><b>两条断言就是全部口径</b>（Kevin 2026-10-05 定方案 A）：</p>
 * <ol>
 *   <li><b>拼团行零变化</b>：对全部 7×7 (from, to)，按来源入口给 batch 的结果与一期
 *       {@code checkAdvance(from, to)} <b>逐一相同</b> —— 收紧或放宽任何一组都红。
 *       一期本就允许任意非终态 → purchase_failed（GZ-JP-106 有意设计），这里不动它。</li>
 *   <li><b>代切行</b>：{@code await_seller_ship → purchase_failed} 允许（卖家取消 / 验货退货的退款入口）。</li>
 * </ol>
 *
 * @author kevin-coder (sensenran-guzi · GZ-SNAP-102)
 */
@Tag("dev")
@DisplayName("GZ-SNAP-102 按来源判定的履约状态机入口")
class GzJpFulfillStateMachineSnapTest {

    private static final String BATCH = GzJpItemSource.BATCH.getCode();
    private static final String SNAP = GzJpItemSource.SNAP.getCode();
    private static final String AWAIT = GzJpFulfillStatus.AWAIT_SELLER_SHIP.getCode();
    private static final String FAILED = GzJpFulfillStatus.PURCHASE_FAILED.getCode();

    @Test
    @DisplayName("★ batch 行：全部 7×7 (from,to) 与一期 checkAdvance(from,to) 逐一相同")
    void batchIdenticalToPhaseOneForAllPairs() {
        List<String> diffs = new ArrayList<>();
        int pairs = 0;
        for (GzJpFulfillStatus from : GzJpFulfillStatus.values()) {
            for (GzJpFulfillStatus to : GzJpFulfillStatus.values()) {
                pairs++;
                GzJpFulfillRejectReason phaseOne = GzJpFulfillStateMachine.checkAdvance(from.getCode(), to.getCode());
                GzJpFulfillRejectReason bySource =
                    GzJpFulfillStateMachine.checkAdvance(BATCH, from.getCode(), to.getCode());
                if (phaseOne != bySource) {
                    diffs.add(from.getCode() + "→" + to.getCode() + " 一期=" + phaseOne + " 按来源=" + bySource);
                }
            }
        }
        assertEquals(49, pairs, "状态数变了，穷举范围要跟着核对");
        assertTrue(diffs.isEmpty(), "拼团行结果偏离一期：" + diffs);
        // 一期规则本身的锚点：拼团行从「等待官方发货」标失败本来就允许（不是二期放开的）
        assertNull(GzJpFulfillStateMachine.checkAdvance(BATCH, AWAIT, FAILED));
        System.out.println("[状态机] batch 49 组与一期逐一相同");
    }

    @Test
    @DisplayName("★ snap 行：await_seller_ship → purchase_failed 允许")
    void snapAwaitSellerShipToFailedAllowed() {
        assertNull(GzJpFulfillStateMachine.checkAdvance(SNAP, AWAIT, FAILED),
            "代切行卖家取消 / 验货退货必须能进购买失败");
        System.out.println("[状态机] snap await_seller_ship → purchase_failed 允许");
    }

    @Test
    @DisplayName("snap 行其余转移沿用一期规则（终态锁死 / 不可回退 / delivered 必须走发货）")
    void snapOtherwiseFollowsPhaseOne() {
        for (GzJpFulfillStatus from : GzJpFulfillStatus.values()) {
            for (GzJpFulfillStatus to : GzJpFulfillStatus.values()) {
                if (AWAIT.equals(from.getCode()) && FAILED.equals(to.getCode())) {
                    continue;
                }
                assertEquals(GzJpFulfillStateMachine.checkAdvance(from.getCode(), to.getCode()),
                    GzJpFulfillStateMachine.checkAdvance(SNAP, from.getCode(), to.getCode()),
                    "snap " + from.getCode() + "→" + to.getCode());
            }
        }
        assertEquals(GzJpFulfillRejectReason.TERMINAL, GzJpFulfillStateMachine.checkAdvance(SNAP, FAILED, AWAIT));
        assertEquals(GzJpFulfillRejectReason.SHIP_REQUIRED,
            GzJpFulfillStateMachine.checkAdvance(SNAP, AWAIT, GzJpFulfillStatus.DELIVERED.getCode()));
    }

    @Test
    @DisplayName("来源为空 / 未知 → 拒（不回落成 batch）")
    void unknownSourceRejected() {
        for (String src : new String[]{null, "", "SNAP", "pindou"}) {
            assertEquals(GzJpFulfillRejectReason.UNKNOWN_CURRENT,
                GzJpFulfillStateMachine.checkAdvance(src, AWAIT, FAILED), "source=" + src);
        }
    }
}
