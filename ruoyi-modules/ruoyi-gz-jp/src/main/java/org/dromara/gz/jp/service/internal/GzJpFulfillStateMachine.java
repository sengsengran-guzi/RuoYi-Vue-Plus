package org.dromara.gz.jp.service.internal;

import org.dromara.gz.jp.domain.enums.GzJpFulfillRejectReason;
import org.dromara.gz.jp.domain.enums.GzJpFulfillStatus;

/**
 * 履约状态机 —— <b>合法转移判定的唯一真源</b>（GZ-JP-106，FLOW:F-JP-03.step2/step3）。
 *
 * <pre>
 *   purchasing 购买中
 *     ├─ purchase_failed 购买失败       ← 分支终态（退款由 GZ-JP-107 接手）
 *     └─ await_seller_ship 等待官方发货
 *          → jp_shipped 日本仓库已发货 → customs 清关中
 *          → cn_sorting 国内分拣中 → delivered 发货完毕（终态，必带运单号）
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
 * <p>纯静态、无状态、无 Spring 依赖 —— 单测直接断言（accept 第 1 条跑
 * {@code GzJpFulfillStateMachineTest}）。</p>
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
