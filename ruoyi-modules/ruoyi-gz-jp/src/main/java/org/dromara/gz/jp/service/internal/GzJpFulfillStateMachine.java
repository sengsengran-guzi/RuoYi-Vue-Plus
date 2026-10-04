package org.dromara.gz.jp.service.internal;

import org.dromara.gz.jp.domain.enums.GzJpFulfillRejectReason;
import org.dromara.gz.jp.domain.enums.GzJpFulfillStatus;
import org.dromara.gz.jp.domain.enums.GzJpItemSource;

/**
 * 履约状态机 —— <b>合法转移判定的唯一真源</b>（GZ-JP-106，FLOW:F-JP-03.step2/step3）。
 *
 * <pre>
 *   主链：purchasing 购买中 → await_seller_ship 等待官方发货 → jp_shipped 日本仓库已发货
 *         → customs 清关中 → cn_sorting 国内分拣中 → delivered 发货完毕（终态，必带运单号）
 *   分支：任一非终态 → purchase_failed 购买失败（终态，退款由 GZ-JP-107 接手；见下文「两个刻意的决定」）
 * </pre>
 *
 * <p><b>三条规则，只有三条</b>：</p>
 * <ol>
 *   <li><b>允许跳过中间态</b>（REQ-EVENT-002）—— 线下现货压根没有「等待官方发货」这一步，
 *       所以只要求 <b>位次严格变大</b>（{@code to.step > from.step}），不要求逐级。</li>
 *   <li><b>不可回退</b>（一期不做回退功能）—— 位次相同或变小即拒。</li>
 *   <li><b>不可跨越终态</b> —— {@code delivered} / {@code purchase_failed} 是终点，
 *       之后任何批量操作都不再碰它（含「再发一次货换个单号」）。</li>
 * </ol>
 *
 * <p><b>位次 {@code step} 直接取自 {@link GzJpFulfillStatus}</b>，本类<b>不重新定义顺序</b> ——
 * 状态取值与先后是枚举的事，本类只管「能不能从 A 走到 B」。</p>
 *
 * <p><b>两个刻意的决定</b>（不是疏漏，评审时请连同理由一起看）：</p>
 * <ul>
 *   <li><b>{@code purchase_failed} 可从任意非终态进入</b>，不限于 {@code purchasing}。
 *       枚举里它与 {@code purchasing} 同为 step 1 —— 它是<b>分支</b>不是前进，所以位次规则对它不适用。
 *       业务上「买到了但卖家取消 / 日方缺货 / 清关退运」都可能在链条中段才暴露，而一期<b>没有回退功能</b>，
 *       若只许从「购买中」标失败，一件卡在「清关中」的货将<b>永远退不了款</b>。
 *       误标的风险由 admin 侧二次确认承担（UI:admin.fulfill_board.mark_failed）。</li>
 *   <li><b>{@code delivered} 不能由「批量推进」达成</b>，必须走「批量发货」（要填快递公司 + 运单号）。
 *       {@link #checkAdvance} 对该目标直接返回
 *       {@link GzJpFulfillRejectReason#SHIP_REQUIRED} —— 这就是 AC「置 delivered 未填单号被拒」的落点，
 *       且拦在<b>写库之前</b>。</li>
 * </ul>
 *
 * <p><b>按来源的入口</b> {@link #checkAdvance(String, String, String)}（GZ-SNAP-102）：拼团行结果与
 * 两参版逐一相同；代切行显式允许 {@code await_seller_ship → purchase_failed}。</p>
 *
 * <p>纯静态、无状态、无 Spring 依赖 —— 单测直接断言（{@code GzJpFulfillStateMachineTest} /
 * {@code GzJpFulfillStateMachineSnapTest}）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-JP-106)
 */
public final class GzJpFulfillStateMachine {

    private GzJpFulfillStateMachine() {
    }

    /**
     * 「批量推进」的转移判定（{@code POST /system/gz/jp/fulfill/advance}）。
     *
     * @param from 行当前 {@code fulfill_status}
     * @param to   目标 {@code fulfill_status}
     * @return {@code null} = 允许（含「已是目标态」，由调用方按 skip 处理）；否则为拒绝原因
     */
    public static GzJpFulfillRejectReason checkAdvance(String from, String to) {
        if (GzJpFulfillStatus.DELIVERED.getCode().equals(to)) {
            // 发货完毕必须带运单号 —— 拦在这里，而不是等写库时才发现 carrier/tracking 为空
            return GzJpFulfillRejectReason.SHIP_REQUIRED;
        }
        return check(from, to);
    }

    /**
     * 按订单行来源判定的「推进」入口（GZ-SNAP-102，ADR-0025 §5：代切行与拼团行共用这一套状态机）。
     *
     * <ul>
     *   <li><b>{@code batch}</b>：结果与 {@link #checkAdvance(String, String)} <b>对全部 (from, to) 逐一相同</b>——
     *       一期规则不收紧也不放宽（{@code GzJpFulfillStateMachineSnapTest} 穷举 7×7 守住）。</li>
     *   <li><b>{@code snap}</b>：代切行买到后停在 {@code await_seller_ship} 直到评价完成，期间卖家取消 /
     *       验货退货都要把行改成 {@code purchase_failed}（FLOW:F-JP-09.step1 / step4），
     *       所以 {@code await_seller_ship → purchase_failed} <b>显式允许</b>；其余转移沿用一期规则。</li>
     *   <li>来源为空 / 未知 → {@link GzJpFulfillRejectReason#UNKNOWN_CURRENT}（不回落成 batch，
     *       与 {@code GzJpOrderItemSourceInvariant} 同一口径）。</li>
     * </ul>
     *
     * <p>★ 这里只判「状态链上走不走得通」。「source=snap 且 trade_status≠rated 不许走 admin 批量入口」
     * 是另一道守卫（FLOW:F-JP-09.step3），挂在 admin 批量入口上，不在本类。</p>
     *
     * @param source 订单行 {@code source}（batch / snap）
     * @param from   行当前 {@code fulfill_status}
     * @param to     目标 {@code fulfill_status}
     * @return {@code null} = 允许；否则为拒绝原因
     */
    public static GzJpFulfillRejectReason checkAdvance(String source, String from, String to) {
        GzJpItemSource src = GzJpItemSource.of(source);
        if (src == null) {
            return GzJpFulfillRejectReason.UNKNOWN_CURRENT;
        }
        if (src == GzJpItemSource.SNAP
            && GzJpFulfillStatus.AWAIT_SELLER_SHIP.getCode().equals(from)
            && GzJpFulfillStatus.PURCHASE_FAILED.getCode().equals(to)) {
            // 代切：卖家取消 / 验货不通过 → 行级退款的入口（REQ-SNAP-032/033）
            return null;
        }
        return checkAdvance(from, to);
    }

    /**
     * 「批量发货」的转移判定（{@code POST /system/gz/jp/fulfill/ship}，目标固定 {@code delivered}）。
     *
     * <p>★ 允许从<b>任意非终态</b>直接发货（含 {@code purchasing}）—— 线下现货当天就能发出。</p>
     *
     * @param from 行当前 {@code fulfill_status}
     * @return {@code null} = 允许；否则为拒绝原因
     */
    public static GzJpFulfillRejectReason checkShip(String from) {
        return check(from, GzJpFulfillStatus.DELIVERED.getCode());
    }

    /**
     * 是否终态（{@code delivered} / {@code purchase_failed}）—— 终态行不再被任何批量操作改动。
     *
     * @param code 状态存库值
     * @return true = 终态
     */
    public static boolean isTerminal(String code) {
        return GzJpFulfillStatus.DELIVERED.getCode().equals(code)
            || GzJpFulfillStatus.PURCHASE_FAILED.getCode().equals(code);
    }

    /**
     * 转移合法性核心判定（两个入口共用）。
     *
     * <p>判定顺序即优先级：目标合法性 → 当前值可识别 → 终态 → 原地 → 失败分支 → 位次。</p>
     *
     * @param from 当前状态
     * @param to   目标状态
     * @return null = 允许；否则拒绝原因
     */
    private static GzJpFulfillRejectReason check(String from, String to) {
        if (!GzJpFulfillStatus.isValid(to)) {
            return GzJpFulfillRejectReason.ILLEGAL_TARGET;
        }
        if (GzJpFulfillStatus.PURCHASING.getCode().equals(to)) {
            // 「购买中」是链路起点（支付回调写入），不是任何人的推进目标
            return GzJpFulfillRejectReason.ILLEGAL_TARGET;
        }
        if (!GzJpFulfillStatus.isValid(from)) {
            return GzJpFulfillRejectReason.UNKNOWN_CURRENT;
        }
        if (isTerminal(from)) {
            return GzJpFulfillRejectReason.TERMINAL;
        }
        if (from.equals(to)) {
            // 已是目标态：合法但无事可做，调用方按「跳过」计数（批量里混入同态行属常态）
            return null;
        }
        if (GzJpFulfillStatus.PURCHASE_FAILED.getCode().equals(to)) {
            // 失败是分支不是前进 —— 位次规则不适用（详见类注释）
            return null;
        }
        int fromStep = stepOf(from);
        int toStep = stepOf(to);
        // ★ 只要求严格变大 —— 中间态可跳过（现货没有「等待官方发货」）
        return toStep > fromStep ? null : GzJpFulfillRejectReason.BACKWARD;
    }

    private static int stepOf(String code) {
        for (GzJpFulfillStatus s : GzJpFulfillStatus.values()) {
            if (s.getCode().equals(code)) {
                return s.getStep();
            }
        }
        // isValid 已在上游把关，这里不可达；返回 0 让位次比较自然拒绝而不是抛错
        return 0;
    }
}
