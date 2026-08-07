package org.dromara.gz.jp.service.internal;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.gz.common.pay.domain.entity.GzPayTransaction;
import org.dromara.gz.common.pay.mapper.GzPayTransactionMapper;
import org.dromara.gz.jp.domain.entity.GzJpOrder;
import org.dromara.gz.jp.domain.entity.GzJpOrderItem;
import org.dromara.gz.jp.domain.entity.GzJpRefund;
import org.dromara.gz.jp.domain.enums.GzJpFulfillRejectReason;
import org.dromara.gz.jp.domain.enums.GzJpFulfillStatus;
import org.dromara.gz.jp.domain.enums.GzJpOrderStatus;
import org.dromara.gz.jp.domain.enums.GzJpRefundSkipReason;
import org.dromara.gz.jp.domain.enums.GzJpRefundStatus;
import org.dromara.gz.jp.exception.GzJpRefundErrorCode;
import org.dromara.gz.jp.mapper.GzJpOrderItemMapper;
import org.dromara.gz.jp.mapper.GzJpOrderMapper;
import org.dromara.gz.jp.mapper.GzJpRefundMapper;
import org.dromara.gz.jp.service.internal.dto.GzJpRefundPlan;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 行级退款的<b>事务段</b>（GZ-JP-107，FLOW:F-JP-04）—— 所有写库动作都收在这里。
 *
 * <p><b>★ 为什么单独一个 Bean</b>：本域的核心难点是「DB 写」与「调微信」<b>不能同一个事务</b>。
 * 同一事务下，微信受理失败会把 {@code refund_failed} 这条审计记录一起回滚掉 ——
 * 于是钱没退成功，系统里却什么痕迹都没有，AC「退款失败要落库、不静默吞」直接失守。
 * 而 Spring 的 {@code @Transactional} 走代理，<b>同类内部自调用不生效</b>，所以事务段必须是独立 Bean
 * （与 {@code PayRefundTxService} 同款拆法）。</p>
 *
 * <p><b>三个事务段</b>：</p>
 * <ol>
 *   <li>{@link #markFailedAndCreateRefunds} —— 事务①：锁行 → 判定 → 置 {@code purchase_failed}
 *       → 建退款单（{@code refunding}）。提交后才去调微信。</li>
 *   <li>{@link #markAccepted} / {@link #markAcceptFailed} —— 事务②：调微信之后回写受理结果。</li>
 *   <li>{@link #applyRefundSuccess} / {@link #applyRefundFailure} —— 退款回调事务：推进终态 + 订单 rollup。</li>
 * </ol>
 *
 * @author kevin-coder (sensenran-guzi · GZ-JP-107)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GzJpRefundTxService {

    /** 单条 UPDATE 的 IN 列表切片大小（同 106） */
    private static final int SQL_CHUNK = 100;

    /** 默认退款原因（★ 客人在微信退款记录里看得到，不要写内部黑话） */
    public static final String DEFAULT_REASON = "拼团商品购买失败，原路退款";

    private final GzJpOrderItemMapper itemMapper;
    private final GzJpOrderMapper orderMapper;
    private final GzJpRefundMapper refundMapper;
    private final GzPayTransactionMapper payTransactionMapper;
    private final GzJpRefundNoGenerator refundNoGenerator;

    // ============================================================
    //  事务① 标记购买失败 + 建退款单
    // ============================================================

    /**
     * 事务①：把行标成 {@code purchase_failed}，并为需要退款的行建退款单（{@code refunding}）。
     *
     * <p><b>顺序刻意如此</b>：① 按 id 升序 FOR UPDATE 锁行（防死锁，两个店员勾重叠的行是真实场景）
     * → ② 批量取订单付款态 → ③ 逐行判定（状态判定<b>全部委托 {@code GzJpFulfillStateMachine}</b>，
     * 本类不写第二套 if）→ ④ 按当前状态分组、带守卫 UPDATE → ⑤ 建退款单。</p>
     *
     * <p><b>「已是 purchase_failed」不是拒绝而是补退款候选</b>：106 的 {@code /advance} 端点本来就允许
     * 把行推成 {@code purchase_failed}（不带退款）。若这里也一律拒掉，那些行的钱<b>永远退不出去</b>、
     * 且没有任何入口能补 —— 所以它们进「补建退款单」分支，靠
     * {@code UNIQUE(tenant_id, order_item_id)} 保证不会退第二次。</p>
     *
     * @param ids        已去重升序的行 id
     * @param reason     退款原因（空则取默认）
     * @param operatorId 操作人 sys_user.id（写 update_by）
     * @param triggeredBy 触发人标识（username / system，落 gz_jp_refund.triggered_by）
     * @return 执行计划（计数 + 拒绝明细 + 待提交微信的退款单）
     */
    @Transactional(rollbackFor = Exception.class)
    public GzJpRefundPlan markFailedAndCreateRefunds(List<Long> ids, String reason,
                                                     Long operatorId, String triggeredBy) {
        String target = GzJpFulfillStatus.PURCHASE_FAILED.getCode();
        GzJpRefundPlan plan = new GzJpRefundPlan();
        plan.setRequested(ids.size());

        // ① 悲观锁加载（ids 已升序 → 并发重叠请求加锁顺序一致 → 不死锁）
        List<GzJpOrderItem> rows = itemMapper.selectByIdsForUpdate(ids);
        Map<Long, GzJpOrderItem> rowMap = new HashMap<>(rows.size());
        for (GzJpOrderItem row : rows) {
            rowMap.put(row.getId(), row);
        }

        // ② 订单付款态（★ 未支付订单的行 fulfill_status 也是 purchasing，必须靠订单判定）
        Map<Long, GzJpOrder> orderMap = loadOrders(rows);

        // ③ 逐行判定
        Map<String, List<Long>> todoByFrom = new LinkedHashMap<>();
        List<Long> alreadyFailed = new ArrayList<>();
        for (Long id : ids) {
            GzJpOrderItem row = rowMap.get(id);
            if (row == null) {
                plan.reject(id, null, GzJpFulfillRejectReason.NOT_FOUND);
                continue;
            }
            GzJpOrder order = orderMap.get(row.getOrderId());
            if (order == null || !GzJpOrderStatus.isPaidLike(order.getBusinessStatus())) {
                // 没付过钱的单没有可退的款 —— 也不该标失败（客人根本没下单成功）
                plan.reject(id, row.getFulfillStatus(), GzJpFulfillRejectReason.ORDER_UNPAID);
                continue;
            }
            if (target.equals(row.getFulfillStatus())) {
                // 已是目标态：状态不用动，但要检查退款有没有做（见方法注释）
                alreadyFailed.add(id);
                continue;
            }
            GzJpFulfillRejectReason reason0 = GzJpFulfillStateMachine.checkAdvance(row.getFulfillStatus(), target);
            if (reason0 != null) {
                plan.reject(id, row.getFulfillStatus(), reason0);
                continue;
            }
            todoByFrom.computeIfAbsent(row.getFulfillStatus(), k -> new ArrayList<>()).add(id);
        }

        // ④ 分组写（每组一条带守卫的 UPDATE；守卫 affected 对不上 → 整批回滚，不糊近似计数）
        List<Long> markedIds = new ArrayList<>();
        for (Map.Entry<String, List<Long>> entry : todoByFrom.entrySet()) {
            String from = entry.getKey();
            for (List<Long> chunk : partition(entry.getValue(), SQL_CHUNK)) {
                int affected = itemMapper.advanceGuarded(chunk, from, target, operatorId);
                if (affected != chunk.size()) {
                    // 行已被 FOR UPDATE 锁住，理论不可达。真发生了说明有人绕过本服务写库 ——
                    // 这是资金链路，宁可整批回滚也不能带着不确定的状态去调微信退款。
                    throw new ServiceException("履约状态已被他人修改（预期 " + chunk.size()
                        + " 行、实际 " + affected + " 行），已回滚，请刷新看板后重试");
                }
                markedIds.addAll(chunk);
            }
        }
        plan.setMarkedFailed(markedIds.size());
        plan.setAlreadyFailed(alreadyFailed.size());

        // ⑤ 建退款单（本次标上的 + 本来就失败但没退过款的）
        List<Long> refundCandidates = new ArrayList<>(markedIds);
        refundCandidates.addAll(alreadyFailed);
        Collections.sort(refundCandidates);
        if (!refundCandidates.isEmpty()) {
            createRefunds(refundCandidates, rowMap, orderMap, resolveReason(reason), triggeredBy, plan);
        }

        // ⑥ 一行没标上、也没有一分钱需要退 = 这次操作整体就是错的，抛错让 admin 看见红字
        if (plan.getMarkedFailed() == 0 && plan.getPending().isEmpty()
            && plan.getImmediateRefundedItemIds().isEmpty() && plan.getRefundsSkipped() == 0) {
            String detail = plan.getRejects().isEmpty() ? "所选行均不可操作" : plan.getRejects().get(0).getReason();
            throw new ServiceException("没有可标记购买失败的商品行：" + detail
                + "（共 " + plan.getRejects().size() + " 行被拒）", GzJpRefundErrorCode.NOTHING_MARKED);
        }

        // ⑦ 零元行不经过微信也不经过回调，rollup 只能在这里做
        rollupOrdersOf(plan.getImmediateRefundedItemIds(), rowMap);
        return plan;
    }

    /**
     * 为候选行建退款单。已有退款单的行按原状态给出 skip 原因（不重复退款）。
     */
    private void createRefunds(List<Long> candidates, Map<Long, GzJpOrderItem> rowMap,
                               Map<Long, GzJpOrder> orderMap, String reason, String triggeredBy,
                               GzJpRefundPlan plan) {
        // 已有退款单的行：给人话原因而不是直接撞唯一键（DuplicateKey 报文 admin 看不懂）
        Map<Long, GzJpRefund> existing = new HashMap<>();
        for (GzJpRefund r : refundMapper.selectByOrderItemIds(candidates)) {
            existing.put(r.getOrderItemId(), r);
        }

        // 原支付流水（按订单批量取一次，别逐行查）+ 已占用退款额（超退兜底）
        Map<Long, GzPayTransaction> txnByOrder = new HashMap<>();
        Map<Long, Long> usedByOrder = new HashMap<>();
        LocalDateTime now = LocalDateTime.now();

        for (Long itemId : candidates) {
            GzJpOrderItem row = rowMap.get(itemId);
            GzJpOrder order = orderMap.get(row.getOrderId());

            GzJpRefund had = existing.get(itemId);
            if (had != null) {
                plan.skip(itemId, had, skipReasonOf(had));
                continue;
            }

            long amount = row.getAmountCent() == null ? 0L : row.getAmountCent();

            // 原支付流水：没有它就没法向微信证明「退的是哪一单」——宁可拒绝也不能凭空造一笔退款
            GzPayTransaction txn = txnByOrder.get(order.getId());
            if (txn == null && !txnByOrder.containsKey(order.getId())) {
                txn = order.getPayTransactionId() == null
                    ? null : payTransactionMapper.selectById(order.getPayTransactionId());
                txnByOrder.put(order.getId(), txn);
            }
            if (txn == null && amount > 0) {
                plan.rejectRefund(itemId, "订单 " + order.getOrderNo() + " 缺少支付流水，无法发起退款（请联系技术）",
                    GzJpRefundErrorCode.PAY_TXN_MISSING);
                continue;
            }

            long orderTotal = txn == null ? order.getTotalAmountCent() : txn.getAmountCent();

            // 超退兜底：Σ 行金额 = 订单总额 且一行至多一条退款单，理论不可达；
            // 真触发说明金额数据已不一致 —— 这是资金安全最后一道闸，宁可拒绝也不提交给微信。
            if (amount > 0) {
                long used = usedByOrder.computeIfAbsent(order.getId(), refundMapper::sumActiveAmountByOrderId);
                if (used + amount > orderTotal) {
                    plan.rejectRefund(itemId, "退款总额将超过原支付单总额（已占用 " + used + " 分 + 本次 "
                            + amount + " 分 > 原单 " + orderTotal + " 分），已拒绝，请核对数据",
                        GzJpRefundErrorCode.REFUND_EXCEEDS_TOTAL);
                    continue;
                }
                usedByOrder.put(order.getId(), used + amount);
            }

            // 零元行：微信不接受 0 元退款，直接置已退款（不调通道、不等回调）
            boolean zero = amount <= 0;
            GzJpRefund refund = GzJpRefund.builder()
                .refundNo(refundNoGenerator.generate())
                .orderId(order.getId())
                .orderItemId(itemId)
                .userId(row.getUserId())
                .outTradeNo(txn == null ? order.getOrderNo() : txn.getOutTradeNo())
                .payTransactionId(txn == null ? null : txn.getId())
                .refundAmountCent(amount)
                .totalAmountCent(orderTotal)
                .status(zero ? GzJpRefundStatus.REFUNDED.getCode() : GzJpRefundStatus.REFUNDING.getCode())
                .reason(reason)
                .attemptCount(0)
                .triggeredBy(triggeredBy)
                .triggeredTime(now)
                .refundedTime(zero ? now : null)
                // ★ @Version 实体：一次性配齐字段再 insert，绝不 insert 后 updateById 补（GZ-BEAN-039 踩过）
                .version(0)
                .delFlag("0")
                .build();
            try {
                refundMapper.insert(refund);
            } catch (DuplicateKeyException e) {
                // 撞 uk_order_item = 并发的另一个请求刚为这一行建了退款单 → 幂等跳过（DB 层保证不重复退款）
                // 撞 uk_refund_no  = Redis 号段落后 DB → 自愈后由调用方重试（本次这一行跳过，不重复扣款）
                refundNoGenerator.reconcileToDbMax();
                GzJpRefund concurrent = refundMapper.selectByOrderItemIds(List.of(itemId))
                    .stream().findFirst().orElse(null);
                log.warn("[gz-jp-refund] 建退款单撞唯一键 item={}（并发或号段落后），已跳过不重复退款: {}",
                    itemId, e.getMessage());
                if (concurrent != null) {
                    plan.skip(itemId, concurrent, GzJpRefundSkipReason.ALREADY_REFUNDING);
                } else {
                    plan.rejectRefund(itemId, "退款单号冲突，请稍后重试", GzJpRefundErrorCode.NOTHING_MARKED);
                }
                continue;
            }

            if (zero) {
                // 行上同步落结果，并记下来在事务尾部做 rollup（零元退款没有回调会来做这件事）
                itemMapper.writeRefundResult(itemId, GzJpRefundStatus.REFUNDED.getCode(), 0L);
                plan.immediateRefunded(itemId, refund);
                log.warn("[gz-jp-refund] 行金额为 0，跳过微信直接置已退款 item={} refund_no={}",
                    itemId, refund.getRefundNo());
            } else {
                itemMapper.writeRefundResult(itemId, GzJpRefundStatus.REFUNDING.getCode(), null);
                plan.pending(itemId, refund);
            }
        }
    }

    // ============================================================
    //  事务② 受理结果回写（调微信之后）
    // ============================================================

    /**
     * 事务②a：微信受理成功 —— 回填微信退款单号 + 累加提交次数，状态仍 {@code refunding} 等回调。
     *
     * @param refundId       退款单 id
     * @param wechatRefundId 微信退款单号
     */
    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRES_NEW)
    public void markAccepted(Long refundId, String wechatRefundId) {
        refundMapper.markAccepted(refundId, wechatRefundId);
    }

    /**
     * 事务②b：微信受理失败 —— 退款单 {@code refund_failed} + 行 {@code refund_status=refund_failed} + 落原因。
     *
     * <p><b>★ 刻意不回滚商品行的 {@code purchase_failed}</b>（与 GZ-PAY 的「受理失败回滚 paid」语义不同）：
     * 货确实没买到，这是既成的履约事实；退款是它的后续动作，失败了要<b>重试</b>而不是把事实抹掉。
     * 回滚反而会让这行重新出现在采购清单里，店员再去日本买一次。</p>
     *
     * <p><b>REQUIRES_NEW</b>：调用方此时不在事务里（事务①已提交），但显式声明避免将来被人包进外层事务后，
     * 失败记录跟着一起回滚 —— 那正是本类拆分要防的事。</p>
     *
     * @param refundId   退款单 id
     * @param itemId     商品行 id
     * @param failReason 失败原因（本方法内截断）
     */
    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRES_NEW)
    public void markAcceptFailed(Long refundId, Long itemId, String failReason) {
        refundMapper.markFailed(refundId, null, trunc(failReason));
        itemMapper.writeRefundResult(itemId, GzJpRefundStatus.REFUND_FAILED.getCode(), null);
    }

    // ============================================================
    //  退款回调事务
    // ============================================================

    /**
     * 退款回调 SUCCESS：退款单 {@code refunding → refunded} + 行落已退金额 + <b>订单 rollup</b>。
     *
     * <p><b>幂等靠 {@link GzJpRefundMapper#markRefunded} 的 WHERE 状态守卫</b>：
     * affected=0 = 并发/重放的回调已经处理过 → 直接返回 false，<b>绝不重复写行、绝不重复 rollup</b>。
     * 不能用「先 select 再 if」——select 与 update 之间的窗口足以让第二个回调插进来。</p>
     *
     * @param refund         已锁定的退款单
     * @param wechatRefundId 回调给的微信退款单号
     * @return true = 本次真的推进了（调用方据此打日志 / 决定回调响应）
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean applyRefundSuccess(GzJpRefund refund, String wechatRefundId) {
        int moved = refundMapper.markRefunded(refund.getId(), wechatRefundId, LocalDateTime.now());
        if (moved == 0) {
            return false;
        }
        // 行上落结果：★ refund_amount_cent 恒 = 退款单金额 = 该行 amount_cent（accept 第 1 条断言这一点）
        itemMapper.writeRefundResult(refund.getOrderItemId(),
            GzJpRefundStatus.REFUNDED.getCode(), refund.getRefundAmountCent());
        rollupOrder(refund.getOrderId());
        return true;
    }

    /**
     * 退款回调 ABNORMAL / CLOSED：退款单 {@code refunding → refund_failed} + 行同步 + 落原因。
     *
     * <p><b>不做 rollup</b> —— 钱没退出去，订单不该显示「部分退款」。</p>
     *
     * @param refund         已锁定的退款单
     * @param wechatRefundId 回调给的微信退款单号
     * @param failReason     失败原因
     * @return true = 本次真的推进了
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean applyRefundFailure(GzJpRefund refund, String wechatRefundId, String failReason) {
        int moved = refundMapper.markFailed(refund.getId(), wechatRefundId, trunc(failReason));
        if (moved == 0) {
            return false;
        }
        itemMapper.writeRefundResult(refund.getOrderItemId(),
            GzJpRefundStatus.REFUND_FAILED.getCode(), null);
        return true;
    }

    /**
     * 重试：{@code refund_failed → refunding}（admin 人工确认后）。
     *
     * @param refundId 退款单 id
     * @param itemId   商品行 id
     * @return true = 本次真的重置成功（false = 状态已变，重试作废）
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean markRetrying(Long refundId, Long itemId) {
        if (refundMapper.markRetrying(refundId) == 0) {
            return false;
        }
        itemMapper.writeRefundResult(itemId, GzJpRefundStatus.REFUNDING.getCode(), null);
        return true;
    }

    // ============================================================
    //  订单状态 rollup（FLOW:F-JP-04.step3）
    // ============================================================

    /**
     * 按<b>已退款行数</b>重算订单状态：全部行已退 → {@code refunded}；有已退但非全部 → {@code partial_refunded}。
     *
     * <p><b>★ 为什么基准是「已退款行数」而不是「购买失败行数」</b>：{@code business_status} 是<b>钱</b>的状态
     * （ADR-0020 §1「订单只管钱」）。行标了失败但退款还在路上 / 退款失败了，钱都还没回到客人手里，
     * 此时显示「部分退款」是在骗客人。用已退款数做基准还自带一个好处：<b>自愈</b> ——
     * 卡住的那笔重试成功后再次 rollup，订单自动从 partial_refunded 走到 refunded。</p>
     *
     * <p><b>并发</b>：同一订单的两个行级退款回调可能同时到。先 {@code SELECT ... FOR UPDATE} 锁订单行，
     * 把 rollup 串行化 —— 否则两边都读到「已退 1 行」，双双算出 partial_refunded，最后一行退完的那次也不例外。</p>
     *
     * <p><b>守卫</b>：只从 {@code paid} / {@code partial_refunded} 推进，
     * 所以 {@code refunded} 是订单级终态，不会被后来的 rollup 降级回去。</p>
     *
     * @param orderId 订单 id
     */
    public void rollupOrder(Long orderId) {
        if (orderId == null) {
            return;
        }
        GzJpOrder order = orderMapper.selectByIdForUpdate(orderId);
        if (order == null || !GzJpOrderStatus.isPaidLike(order.getBusinessStatus())) {
            // 未付款 / 已取消的单没有可 rollup 的资金状态
            return;
        }
        int total = itemMapper.countByOrderId(orderId);
        int refunded = itemMapper.countRefundedByOrderId(orderId);
        if (total <= 0 || refunded <= 0) {
            return;
        }
        String target = refunded >= total
            ? GzJpOrderStatus.REFUNDED.getCode()
            : GzJpOrderStatus.PARTIAL_REFUNDED.getCode();
        if (target.equals(order.getBusinessStatus())) {
            return;
        }
        int moved = orderMapper.markRefundRollup(orderId, target);
        log.info("[gz-jp-refund] 订单 rollup order_id={} {}/{} 行已退款 → {}（affected={}）",
            orderId, refunded, total, target, moved);
    }

    /** 对一批商品行所属的订单去重后逐单 rollup（零元退款没有回调，只能在事务①尾部做）。 */
    private void rollupOrdersOf(List<Long> itemIds, Map<Long, GzJpOrderItem> rowMap) {
        if (ObjectUtil.isEmpty(itemIds)) {
            return;
        }
        Set<Long> orderIds = new HashSet<>();
        for (Long itemId : itemIds) {
            GzJpOrderItem row = rowMap.get(itemId);
            if (row != null) {
                orderIds.add(row.getOrderId());
            }
        }
        List<Long> sorted = new ArrayList<>(orderIds);
        // 升序锁订单行，与其他 rollup 路径加锁顺序一致
        Collections.sort(sorted);
        for (Long orderId : sorted) {
            rollupOrder(orderId);
        }
    }

    // ============================================================
    //  工具
    // ============================================================

    private Map<Long, GzJpOrder> loadOrders(List<GzJpOrderItem> rows) {
        Set<Long> orderIds = new HashSet<>();
        for (GzJpOrderItem row : rows) {
            if (row.getOrderId() != null) {
                orderIds.add(row.getOrderId());
            }
        }
        if (orderIds.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Long, GzJpOrder> map = new HashMap<>();
        for (GzJpOrder order : orderMapper.selectByIds(orderIds)) {
            map.put(order.getId(), order);
        }
        return map;
    }

    private static GzJpRefundSkipReason skipReasonOf(GzJpRefund had) {
        if (GzJpRefundStatus.REFUNDED.getCode().equals(had.getStatus())) {
            return had.getRefundAmountCent() != null && had.getRefundAmountCent() <= 0
                ? GzJpRefundSkipReason.ZERO_AMOUNT : GzJpRefundSkipReason.ALREADY_REFUNDED;
        }
        if (GzJpRefundStatus.REFUND_FAILED.getCode().equals(had.getStatus())) {
            return GzJpRefundSkipReason.PREVIOUS_ATTEMPT_FAILED;
        }
        return GzJpRefundSkipReason.ALREADY_REFUNDING;
    }

    private static String resolveReason(String reason) {
        return StrUtil.isBlank(reason) ? DEFAULT_REASON : StrUtil.trim(reason);
    }

    private static String trunc(String s) {
        if (s == null) {
            return null;
        }
        return s.length() > 480 ? s.substring(0, 480) : s;
    }

    private static <T> List<List<T>> partition(List<T> list, int size) {
        List<List<T>> chunks = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size) {
            chunks.add(list.subList(i, Math.min(list.size(), i + size)));
        }
        return chunks;
    }
}
