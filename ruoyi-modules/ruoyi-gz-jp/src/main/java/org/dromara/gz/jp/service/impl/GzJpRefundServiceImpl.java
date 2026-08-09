package org.dromara.gz.jp.service.impl;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.gz.common.domain.vo.GzUserVO;
import org.dromara.gz.common.pay.config.WechatPayProperties;
import org.dromara.gz.common.pay.domain.entity.GzPayCallbackLog;
import org.dromara.gz.common.pay.domain.entity.GzPayTransaction;
import org.dromara.gz.common.pay.mapper.GzPayCallbackLogMapper;
import org.dromara.gz.common.pay.mapper.GzPayTransactionMapper;
import org.dromara.gz.common.pay.service.IGzPayShippingService;
import org.dromara.gz.common.pay.service.internal.IWechatPayClient;
import org.dromara.gz.common.pay.service.internal.IWechatPayClient.NotifyContext;
import org.dromara.gz.common.pay.service.internal.IWechatPayClient.RefundCallbackResult;
import org.dromara.gz.common.pay.service.internal.IWechatPayClient.RefundRequest;
import org.dromara.gz.common.pay.service.internal.IWechatPayClient.RefundResult;
import org.dromara.gz.common.service.IGzUserService;
import org.dromara.gz.jp.config.GzJpPayProperties;
import org.dromara.gz.jp.domain.bo.GzJpMarkFailedBo;
import org.dromara.gz.jp.domain.bo.GzJpRefundQueryBo;
import org.dromara.gz.jp.domain.dto.GzJpRefundRow;
import org.dromara.gz.jp.domain.entity.GzJpOrder;
import org.dromara.gz.jp.domain.entity.GzJpOrderItem;
import org.dromara.gz.jp.domain.entity.GzJpRefund;
import org.dromara.gz.jp.domain.enums.GzJpOrderStatus;
import org.dromara.gz.jp.domain.enums.GzJpRefundStatus;
import org.dromara.gz.jp.domain.vo.GzJpMarkFailedResultVO;
import org.dromara.gz.jp.domain.vo.GzJpRefundAdminVO;
import org.dromara.gz.jp.domain.vo.GzJpRefundLineVO;
import org.dromara.gz.jp.exception.GzJpRefundErrorCode;
import org.dromara.gz.jp.mapper.GzJpOrderItemMapper;
import org.dromara.gz.jp.mapper.GzJpOrderMapper;
import org.dromara.gz.jp.mapper.GzJpRefundMapper;
import org.dromara.gz.jp.service.IGzJpRefundService;
import org.dromara.gz.jp.service.internal.GzJpRefundTxService;
import org.dromara.gz.jp.service.internal.dto.GzJpRefundPlan;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 拼团行级退款服务实现（GZ-JP-107，FLOW:F-JP-04，ADR-0020 §3）。
 *
 * <p><b>受保护区（本卡一行未动，git diff 可证）</b>：{@code PayRefundController} /
 * {@code PayRefundServiceImpl} / {@code PayRefundTxService} —— 拼豆与回收在用的线上资金链路。
 * 本类只调通道层 {@code IWechatPayClient.refund}。</p>
 *
 * <p><b>事务编排（本类最重要的结构）</b>：</p>
 * <pre>
 *   事务①  GzJpRefundTxService.markFailedAndCreateRefunds
 *           锁行 → 判定 → 置 purchase_failed → 建退款单(refunding) → 提交
 *              ↓（★ 出事务，因为受理失败不能连带回滚失败记录）
 *   事务外  逐张 IWechatPayClient.refund(outTradeNo, outRefundNo, 行金额, 原单总额, ...)
 *              ├─ 受理成功 → 事务②a markAccepted：落微信单号，状态仍 refunding，等回调
 *              └─ 受理失败 → 事务②b markAcceptFailed：refund_failed + fail_reason（★ 不回滚 purchase_failed）
 *              ↓
 *   回调事务 handleRefundNotify：锁退款单 → 守卫 UPDATE → 行落已退金额 → 订单 rollup
 * </pre>
 *
 * <p><b>崩溃窗口与兜底</b>：事务①提交后、调微信前进程挂掉，会留下一张永远停在 {@code refunding} 的
 * 退款单（钱没退出去）。本项目 <b>prod 未部署 SnailJob</b>（25 个 @JobExecutor 一个都没跑），
 * 自动补偿任务写了也不会执行 —— 所以兜底做成<b>人可操作的</b>：退款单列表把失败与长时间未回调的单
 * 排在最前，admin 点「重新发起」即可（{@link #retry} 对超过静默期的 {@code refunding} 也放行）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-JP-107)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GzJpRefundServiceImpl implements IGzJpRefundService {

    /** 单次批量上限（与 106 批量推进同口径） */
    private static final int BATCH_MAX = 200;

    /** 列表分页默认 / 上限 */
    private static final int PAGE_DEFAULT = 20;
    private static final int PAGE_MAX = 200;

    /** 拼团退款回调路径（★ 必须与 GZ-PAY 的 /api/pay/v3/refund-notify 不同，见 GzJpPayProperties） */
    public static final String JP_REFUND_NOTIFY_PATH = "/api/gz/jp/pay/refund-notify";

    /** 回调审计日志类型（gz_pay_callback_log.callback_type，与 payment / refund 并列的独立 key） */
    private static final String CALLBACK_TYPE = "jp_refund";
    private static final String CB_RECEIVED = "received";
    private static final String CB_PROCESSED = "processed";
    private static final String CB_DUPLICATED = "duplicated";
    private static final String CB_FAILED = "failed";

    /**
     * {@code refunding} 静默期：超过这么久还没等到回调，才允许 admin 重新发起。
     *
     * <p>太短会让店员在回调正常在途时狂点（微信按 out_refund_no 幂等，不会重复扣款，但会刷日志）；
     * 太长则崩溃窗口里那张单要等很久才能救。微信退款通常几分钟内回调，10 分钟足够区分。</p>
     */
    private static final Duration REFUNDING_SILENCE = Duration.ofMinutes(10);

    private final GzJpRefundTxService txService;
    private final GzJpRefundMapper refundMapper;
    private final GzJpOrderMapper orderMapper;
    private final GzJpOrderItemMapper itemMapper;
    private final IWechatPayClient wechatPayClient;
    private final WechatPayProperties payProperties;
    private final GzJpPayProperties jpPayProperties;
    private final GzPayCallbackLogMapper callbackLogMapper;
    private final IGzUserService userService;
    /** 发货上报收口（标了购买失败可能让整单就此发完，见 {@link #settleShippingIfAllDone}） */
    private final GzPayTransactionMapper payTransactionMapper;
    private final IGzPayShippingService shippingService;

    // ============================================================
    //  标记购买失败 + 发起行级退款（FLOW:F-JP-04.step1/step2）
    // ============================================================

    @Override
    public GzJpMarkFailedResultVO markPurchaseFailedAndRefund(GzJpMarkFailedBo bo, Long operatorId,
                                                              String triggeredBy) {
        if (bo == null) {
            throw new ServiceException("请求参数为空");
        }
        List<Long> ids = normalizeIds(bo.getItemIds());

        // 真实退款前先把回调地址解析出来：real 模式下配错 / 没配就 fail-fast，
        // 绝不「先把钱退了再发现回调回不来」——那种单会永远停在退款中。
        String notifyUrl = resolveRefundNotifyUrl();

        // ── 事务① ──（提交后才调微信）
        GzJpRefundPlan plan = txService.markFailedAndCreateRefunds(ids, bo.getReason(), operatorId, triggeredBy);

        // ★ 标了购买失败之后，整单可能就此「全部落定」了 —— 拼团最典型的收尾正是
        //   「边到边发，最后剩几款买不到、标失败退款」。那条路径上原先一次发货上报都没有，
        //   于是订单实际结束、微信侧却永远停在「部分发货」（只有 is_all_delivered=true 才收口）。
        //   放在事务①提交之后：此刻 purchase_failed 已落库，countUnfinished 才算得准。
        settleShippingIfAllDone(ids);

        // ── 事务外：逐张提交微信 ──
        int accepted = 0;
        int failed = 0;
        List<GzJpRefundLineVO> acceptedLines = new ArrayList<>();
        for (GzJpRefundPlan.PendingRefund p : plan.getPending()) {
            GzJpRefund refund = p.getRefund();
            GzJpRefundLineVO line = baseLine(p.getItemId(), refund);
            try {
                RefundResult result = wechatPayClient.refund(new RefundRequest(
                    refund.getOutTradeNo(),
                    refund.getRefundNo(),
                    // ★★ 行级部分退款的落点：本次退【该行】金额，原单总额单独传给微信做校验
                    refund.getRefundAmountCent(),
                    refund.getTotalAmountCent(),
                    refund.getReason(),
                    notifyUrl));
                txService.markAccepted(refund.getId(), result.refundId());
                line.setAccepted(true);
                line.setRefundStatus(GzJpRefundStatus.REFUNDING.getCode());
                line.setRefundStatusLabel(GzJpRefundStatus.REFUNDING.getLabel());
                accepted++;
                log.info("[gz-jp-refund] 退款受理成功 refund_no={} item={} amount={}/{} wechat_refund_id={} status={}",
                    refund.getRefundNo(), p.getItemId(), refund.getRefundAmountCent(),
                    refund.getTotalAmountCent(), result.refundId(), result.status());
            } catch (Exception e) {
                // ★ AC「退款失败有明确落库状态 + admin 可见，不静默吞」的落点。
                //   刻意【不回滚 purchase_failed】：货确实没买到，那是既成的履约事实；
                //   退款失败要重试，不是把事实抹掉（回滚会让这行重新出现在采购清单里）。
                String msg = StrUtil.blankToDefault(e.getMessage(), e.getClass().getSimpleName());
                txService.markAcceptFailed(refund.getId(), p.getItemId(), msg);
                line.setAccepted(false);
                line.setRefundStatus(GzJpRefundStatus.REFUND_FAILED.getCode());
                line.setRefundStatusLabel(GzJpRefundStatus.REFUND_FAILED.getLabel());
                line.setFailReason(msg);
                failed++;
                log.error("[gz-jp-refund] 退款受理失败已落 refund_failed refund_no={} item={} amount={}: {}",
                    refund.getRefundNo(), p.getItemId(), refund.getRefundAmountCent(), msg, e);
            }
            acceptedLines.add(line);
        }

        return buildResult(plan, acceptedLines, accepted, failed);
    }

    /**
     * 标完购买失败后，把「已经全部落定」的订单的发货上报收口为 {@code is_all_delivered=true}。
     *
     * <p>「落定」= 该订单再没有 {@code fulfill_status} 处于 delivered / purchase_failed 之外的行。
     * 整单一个包裹都没发过（全部购买失败）时没有发货任务行，{@code markAllDelivered} 自己会跳过。</p>
     *
     * <p><b>全程吞异常</b>：购买失败与退款是既成事实，收口只是上报侧的次要动作，
     * 任何问题都不能让本次操作失败（同 {@code IGzPayShippingService.enqueue} 的口径）。</p>
     *
     * @param requestedIds 本次请求的商品行 id（用它反查涉及哪些订单；被拒的行一起查也无害，收口是幂等的）
     */
    private void settleShippingIfAllDone(List<Long> requestedIds) {
        try {
            List<GzJpOrderItem> rows = itemMapper.selectByIds(requestedIds);
            Set<Long> orderIds = new LinkedHashSet<>();
            for (GzJpOrderItem row : rows) {
                if (row.getOrderId() != null) {
                    orderIds.add(row.getOrderId());
                }
            }
            for (Long orderId : orderIds) {
                if (itemMapper.countUnfinishedByOrderId(orderId) != 0) {
                    continue;
                }
                GzJpOrder order = orderMapper.selectById(orderId);
                if (order == null || order.getPayTransactionId() == null) {
                    continue;
                }
                GzPayTransaction txn = payTransactionMapper.selectById(order.getPayTransactionId());
                if (txn == null || StrUtil.isBlank(txn.getTransactionId())) {
                    continue;
                }
                if (shippingService.markAllDelivered(txn.getTransactionId())) {
                    log.info("[gz-jp-refund] 订单 {} 因购买失败而全部落定，发货上报已收口", orderId);
                }
            }
        } catch (Exception e) {
            log.error("[gz-jp-refund] 发货收口检查失败（已忽略，不影响购买失败/退款）: {}", e.getMessage(), e);
        }
    }

    /**
     * 组装返回：履约侧与退款侧<b>分开两组计数</b>。
     *
     * <p>合成一个「成功 N 行」会掩盖最危险的情况 ——「状态标成功了但退款失败了」
     * （客人看到购买失败却没收到钱）。</p>
     */
    private GzJpMarkFailedResultVO buildResult(GzJpRefundPlan plan, List<GzJpRefundLineVO> submitted,
                                               int accepted, int failed) {
        GzJpMarkFailedResultVO vo = new GzJpMarkFailedResultVO();
        vo.setRequested(plan.getRequested());
        vo.setMarkedFailed(plan.getMarkedFailed());
        vo.setAlreadyFailed(plan.getAlreadyFailed());
        vo.getRejects().addAll(plan.getRejects());
        vo.setRejected(vo.getRejects().size());

        vo.setRefundsCreated(plan.getPending().size() + plan.getImmediate().size());
        vo.setRefundsAccepted(accepted);
        vo.setRefundsFailed(failed);
        vo.setRefundsSkipped(plan.getRefundsSkipped());

        long total = 0L;
        vo.getRefunds().addAll(submitted);
        for (GzJpRefundLineVO line : submitted) {
            total += line.getRefundAmountCent() == null ? 0L : line.getRefundAmountCent();
        }
        // 零元行（不经过微信，事务①内已置已退款）
        for (GzJpRefundPlan.PendingRefund p : plan.getImmediate()) {
            GzJpRefundLineVO line = baseLine(p.getItemId(), p.getRefund());
            line.setAccepted(true);
            line.setRefundStatus(GzJpRefundStatus.REFUNDED.getCode());
            line.setRefundStatusLabel(GzJpRefundStatus.REFUNDED.getLabel());
            vo.getRefunds().add(line);
        }
        // 本次没花钱的行（之前已退 / 正在退 / 上次失败）
        for (GzJpRefundPlan.SkippedRefund s : plan.getSkipped()) {
            GzJpRefundLineVO line = baseLine(s.getItemId(), s.getExisting());
            line.setAccepted(false);
            line.setRefundStatus(s.getExisting().getStatus());
            line.setRefundStatusLabel(GzJpRefundStatus.labelOf(s.getExisting().getStatus()));
            line.setFailReason(s.getExisting().getFailReason());
            line.setSkipReasonCode(s.getReason().name());
            line.setSkipReason(s.getReason().getMessage());
            vo.getRefunds().add(line);
        }
        // 退款侧被拒（缺支付流水 / 会超退）—— 履约状态可能已经标上了，必须显式告知
        for (GzJpRefundPlan.RefundReject r : plan.getRefundRejects()) {
            GzJpRefundLineVO line = new GzJpRefundLineVO();
            line.setItemId(r.getItemId());
            line.setAccepted(false);
            line.setSkipReasonCode(String.valueOf(r.getErrorCode()));
            line.setSkipReason(r.getMessage());
            line.setFailReason(r.getMessage());
            vo.getRefunds().add(line);
        }
        vo.setRefundAmountCentTotal(total);
        log.info("[gz-jp-refund] MARK-FAILED requested={} marked={} alreadyFailed={} rejected={} | "
                + "refundsCreated={} accepted={} failed={} skipped={} amount={}分",
            vo.getRequested(), vo.getMarkedFailed(), vo.getAlreadyFailed(), vo.getRejected(),
            vo.getRefundsCreated(), vo.getRefundsAccepted(), vo.getRefundsFailed(),
            vo.getRefundsSkipped(), vo.getRefundAmountCentTotal());
        return vo;
    }

    private GzJpRefundLineVO baseLine(Long itemId, GzJpRefund refund) {
        GzJpRefundLineVO line = new GzJpRefundLineVO();
        line.setItemId(itemId);
        if (refund != null) {
            line.setRefundId(refund.getId());
            line.setRefundNo(refund.getRefundNo());
            line.setRefundAmountCent(refund.getRefundAmountCent());
        }
        return line;
    }

    // ============================================================
    //  退款回调（FLOW:F-JP-04.step3）
    // ============================================================

    /**
     * {@inheritDoc}
     *
     * <p><b>整个回调是一个事务</b>：不加 {@code @Transactional} 的话，
     * {@link GzJpRefundMapper#selectByRefundNoForUpdate} 那句 {@code FOR UPDATE} 在 autocommit 下
     * <b>语句一结束锁就释放了</b> —— 等于没锁，两个并发回调会一起走完判定。
     * （功能上仍靠守卫 UPDATE 兜底不会重复退，但那是"最后一道"而不是"唯一一道"；
     * 资金链路不该只剩一层。）</p>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean handleRefundNotify(NotifyContext ctx) {
        // ① 验签解密（失败 throw → controller 写 failed 审计 + 401，不静默）
        RefundCallbackResult result = wechatPayClient.parseAndVerifyRefundNotify(ctx);
        writeCallbackLog(result.outTradeNo(), result.decryptedBody(), ctx.signature(), CB_RECEIVED, null);

        // ② 锁退款单（按 out_refund_no = 我们的 refund_no）
        GzJpRefund refund = refundMapper.selectByRefundNoForUpdate(result.outRefundNo());
        if (refund == null) {
            writeCallbackLog(result.outTradeNo(), result.decryptedBody(), ctx.signature(),
                CB_FAILED, "拼团退款单不存在: " + result.outRefundNo());
            log.error("[gz-jp-refund] 退款回调找不到拼团退款单 out_refund_no={}（若它以 RF- 开头，"
                + "说明是 GZ-PAY 的单被错误路由到了拼团回调地址）", result.outRefundNo());
            return false;
        }

        // ③ 幂等：已终态直接成功，不重复处理（★ 微信重发是常态，不是异常）
        if (!GzJpRefundStatus.REFUNDING.getCode().equals(refund.getStatus())) {
            writeCallbackLog(result.outTradeNo(), result.decryptedBody(), ctx.signature(),
                CB_DUPLICATED, "退款单已终态（" + refund.getStatus() + "），重复回调");
            log.info("[gz-jp-refund] 重复退款回调（已终态）refund_no={} status={}",
                refund.getRefundNo(), refund.getStatus());
            return true;
        }

        if ("SUCCESS".equalsIgnoreCase(result.refundStatus())) {
            // ④ SUCCESS → 退款单 refunded + 行落已退金额 + 订单 rollup（全部在一个事务里）
            boolean moved = txService.applyRefundSuccess(refund, result.refundId());
            if (!moved) {
                writeCallbackLog(result.outTradeNo(), result.decryptedBody(), ctx.signature(),
                    CB_DUPLICATED, "退款单守卫推进冲突（affected=0），并发回调已处理");
                log.info("[gz-jp-refund] 退款单推进冲突视为重复 refund_no={}", refund.getRefundNo());
                return true;
            }
            writeCallbackLog(result.outTradeNo(), result.decryptedBody(), ctx.signature(), CB_PROCESSED, null);
            log.info("[gz-jp-refund] 退款成功 refund_no={} item={} amount={}分 wechat_refund_id={}",
                refund.getRefundNo(), refund.getOrderItemId(), refund.getRefundAmountCent(), result.refundId());
        } else {
            // ⑤ ABNORMAL / CLOSED → refund_failed 留人工（不 rollup：钱没退出去，订单不该显示部分退款）
            String reason = "微信退款回调 refund_status=" + result.refundStatus();
            txService.applyRefundFailure(refund, result.refundId(), reason);
            writeCallbackLog(result.outTradeNo(), result.decryptedBody(), ctx.signature(),
                CB_PROCESSED, reason + "，已标 refund_failed 留人工介入");
            log.error("[gz-jp-refund] 退款失败（回调 {}）refund_no={} item={} —— 留 admin 重新发起",
                result.refundStatus(), refund.getRefundNo(), refund.getOrderItemId());
        }
        return true;
    }

    /**
     * 写回调审计日志 —— 失败仅记 ERROR、<b>绝不向上抛</b>（同 GZ-PAY 口径）。
     *
     * <p>审计 INSERT 若向上透传异常，会让退款回调整笔回滚 → 状态不推进 → 微信重试 → 又失败。
     * 审计写不进去是运维问题，不该阻断退款确认。</p>
     */
    private void writeCallbackLog(String outTradeNo, String rawBody, String signature,
                                  String processStatus, String processError) {
        try {
            callbackLogMapper.insert(GzPayCallbackLog.builder()
                .outTradeNo(outTradeNo)
                .callbackType(CALLBACK_TYPE)
                .rawBody(rawBody == null ? "" : rawBody)
                .signature(signature)
                .processStatus(processStatus)
                .processError(trunc(processError))
                .build());
        } catch (Exception e) {
            log.error("[gz-jp-refund] 写退款回调审计日志失败（已忽略，不影响退款确认）out_trade_no={} status={}: {}",
                outTradeNo, processStatus, e.getMessage(), e);
        }
    }

    // ============================================================
    //  重试（admin 人工确认后）
    // ============================================================

    @Override
    public GzJpRefundAdminVO retry(Long refundId, String triggeredBy) {
        if (refundId == null) {
            throw new ServiceException("请指定退款单", GzJpRefundErrorCode.REFUND_NOT_FOUND);
        }
        String notifyUrl = resolveRefundNotifyUrl();

        GzJpRefund refund = refundMapper.selectById(refundId);
        if (refund == null) {
            throw new ServiceException("退款单不存在: " + refundId, GzJpRefundErrorCode.REFUND_NOT_FOUND);
        }
        if (GzJpRefundStatus.REFUNDED.getCode().equals(refund.getStatus())) {
            // 钱已经退到客人微信了，重试等于退第二次 —— 绝不放行
            throw new ServiceException("该退款单已完成，不可重复发起", GzJpRefundErrorCode.REFUND_RETRY_NOT_ALLOWED);
        }
        boolean stale = refund.getTriggeredTime() != null
            && refund.getTriggeredTime().isBefore(LocalDateTime.now().minus(REFUNDING_SILENCE));
        if (GzJpRefundStatus.REFUNDING.getCode().equals(refund.getStatus())) {
            if (!stale) {
                throw new ServiceException("该退款单刚发起、正在等待微信回调，请 "
                    + REFUNDING_SILENCE.toMinutes() + " 分钟后再试",
                    GzJpRefundErrorCode.REFUND_RETRY_NOT_ALLOWED);
            }
            // 超过静默期仍无回调：多半是「事务①提交后进程挂了、根本没提交给微信」，或回调丢了。
            // 微信按 out_refund_no 幂等，重发同一个号安全 —— 已退成功会原样返回，不会退第二次。
            log.warn("[gz-jp-refund] 退款单超过 {} 分钟未收到回调，人工重新提交 refund_no={}",
                REFUNDING_SILENCE.toMinutes(), refund.getRefundNo());
        } else if (!txService.markRetrying(refundId, refund.getOrderItemId())) {
            throw new ServiceException("退款单状态已变化，请刷新后重试", GzJpRefundErrorCode.REFUND_RETRY_NOT_ALLOWED);
        }

        try {
            RefundResult result = wechatPayClient.refund(new RefundRequest(
                refund.getOutTradeNo(),
                // ★ 复用同一个 refund_no —— 微信按 out_refund_no 幂等，换号可能造成同一行退两次
                refund.getRefundNo(),
                refund.getRefundAmountCent(),
                refund.getTotalAmountCent(),
                refund.getReason(),
                notifyUrl));
            txService.markAccepted(refundId, result.refundId());
            log.info("[gz-jp-refund] 重新发起退款受理成功 refund_no={} by={} wechat_refund_id={}",
                refund.getRefundNo(), triggeredBy, result.refundId());
        } catch (Exception e) {
            String msg = StrUtil.blankToDefault(e.getMessage(), e.getClass().getSimpleName());
            txService.markAcceptFailed(refundId, refund.getOrderItemId(), msg);
            log.error("[gz-jp-refund] 重新发起退款仍失败 refund_no={} by={}: {}",
                refund.getRefundNo(), triggeredBy, msg, e);
            throw new ServiceException("重新发起退款失败：" + msg);
        }
        return toAdminVO(toRow(refundMapper.selectById(refundId)), Collections.emptyMap());
    }

    // ============================================================
    //  退款单列表（GZ-JP-108 消费）
    // ============================================================

    @Override
    public TableDataInfo<GzJpRefundAdminVO> selectAdminPage(GzJpRefundQueryBo query, PageQuery pageQuery) {
        GzJpRefundQueryBo q = query == null ? new GzJpRefundQueryBo() : query;
        if (StrUtil.isNotBlank(q.getStatus()) && !GzJpRefundStatus.isValid(q.getStatus())) {
            // 非法值报错而不是静默忽略：忽略会让结果集看起来「更多」而不是「更少」（同 106 口径）
            throw new ServiceException("非法的退款状态筛选值：" + q.getStatus());
        }
        Collection<Long> userIds = resolveUserIds(q);
        if (userIds != null && userIds.isEmpty()) {
            // 关键词没匹配到任何客人 → 空结果，别退化成「不筛选」把全部退款单倒出来
            return TableDataInfo.build(new ArrayList<>());
        }
        LocalDateTime beginTime = q.getBeginDate() == null ? null : q.getBeginDate().atStartOfDay();
        LocalDateTime endTime = q.getEndDate() == null ? null : LocalDateTime.of(q.getEndDate(), LocalTime.MAX);

        Page<GzJpRefundRow> page = refundMapper.selectAdminPage(
            buildPage(pageQuery), StrUtil.trimToNull(q.getStatus()), StrUtil.trimToNull(q.getOrderNo()),
            userIds, beginTime, endTime);

        Map<Long, GzUserVO> userMap = loadUsers(page.getRecords());
        List<GzJpRefundAdminVO> vos = new ArrayList<>(page.getRecords().size());
        for (GzJpRefundRow row : page.getRecords()) {
            vos.add(toAdminVO(row, userMap));
        }
        Page<GzJpRefundAdminVO> voPage = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        voPage.setRecords(vos);
        return TableDataInfo.build(voPage);
    }

    private GzJpRefundAdminVO toAdminVO(GzJpRefundRow row, Map<Long, GzUserVO> userMap) {
        GzJpRefundAdminVO vo = new GzJpRefundAdminVO();
        vo.setId(row.getId());
        vo.setRefundNo(row.getRefundNo());
        vo.setOrderId(row.getOrderId());
        vo.setOrderNo(row.getOrderNo());
        vo.setBusinessStatus(row.getBusinessStatus());
        vo.setBusinessStatusLabel(row.getBusinessStatus() == null ? null
            : GzJpOrderStatus.labelOf(row.getBusinessStatus()));
        vo.setOrderItemId(row.getOrderItemId());
        vo.setUserId(row.getUserId());
        vo.setOutTradeNo(row.getOutTradeNo());
        vo.setRefundAmountCent(row.getRefundAmountCent());
        vo.setTotalAmountCent(row.getTotalAmountCent());
        vo.setWechatRefundId(row.getWechatRefundId());
        vo.setStatus(row.getStatus());
        vo.setStatusLabel(GzJpRefundStatus.labelOf(row.getStatus()));
        vo.setReason(row.getReason());
        vo.setFailReason(row.getFailReason());
        vo.setAttemptCount(row.getAttemptCount());
        vo.setTriggeredBy(row.getTriggeredBy());
        vo.setTriggeredTime(row.getTriggeredTime());
        vo.setRefundedTime(row.getRefundedTime());
        vo.setRetryable(retryable(row.getStatus(), row.getTriggeredTime()));
        GzUserVO user = userMap.get(row.getUserId());
        if (user != null) {
            vo.setUserNickname(user.getNickname());
            vo.setUserMobile(user.getMobile());
            vo.setUserNo(user.getUserNo());
        }
        return vo;
    }

    /** 后端算好「能不能重试」，前端别自己判状态（判错会让店员对着已成功的单点重试）。 */
    private boolean retryable(String status, LocalDateTime triggeredTime) {
        if (GzJpRefundStatus.REFUND_FAILED.getCode().equals(status)) {
            return true;
        }
        if (!GzJpRefundStatus.REFUNDING.getCode().equals(status)) {
            return false;
        }
        return triggeredTime != null && triggeredTime.isBefore(LocalDateTime.now().minus(REFUNDING_SILENCE));
    }

    private GzJpRefundRow toRow(GzJpRefund r) {
        GzJpRefundRow row = new GzJpRefundRow();
        if (r == null) {
            return row;
        }
        row.setId(r.getId());
        row.setRefundNo(r.getRefundNo());
        row.setOrderId(r.getOrderId());
        row.setOrderItemId(r.getOrderItemId());
        row.setUserId(r.getUserId());
        row.setOutTradeNo(r.getOutTradeNo());
        row.setRefundAmountCent(r.getRefundAmountCent());
        row.setTotalAmountCent(r.getTotalAmountCent());
        row.setWechatRefundId(r.getWechatRefundId());
        row.setStatus(r.getStatus());
        row.setReason(r.getReason());
        row.setFailReason(r.getFailReason());
        row.setAttemptCount(r.getAttemptCount());
        row.setTriggeredBy(r.getTriggeredBy());
        row.setTriggeredTime(r.getTriggeredTime());
        row.setRefundedTime(r.getRefundedTime());
        GzJpOrder order = r.getOrderId() == null ? null : orderMapper.selectById(r.getOrderId());
        if (order != null) {
            row.setOrderNo(order.getOrderNo());
            row.setBusinessStatus(order.getBusinessStatus());
        }
        return row;
    }

    // ============================================================
    //  GZ-PAY 全额退款旁路同步
    // ============================================================

    @Override
    public void onFullRefundedByPayDomain(String orderNo, String outTradeNo) {
        if (StrUtil.isBlank(orderNo)) {
            log.warn("[gz-jp-refund] GZ-PAY 全额退款回调缺 business_order_no out_trade_no={}，无法定位拼团订单",
                outTradeNo);
            return;
        }
        GzJpOrder order = orderMapper.selectByOrderNoForUpdate(orderNo);
        if (order == null) {
            log.warn("[gz-jp-refund] GZ-PAY 全额退款找不到拼团订单 order_no={} out_trade_no={}", orderNo, outTradeNo);
            return;
        }
        // 行上补退款结果：只碰还没有退款记录的行（走过行级退款的行有自己的金额，不能被覆盖），
        // 且【不碰 fulfill_status】—— 货走到哪是另一根轴，全额退款不代表这些货没买到
        int touched = itemMapper.markAllRefundedForFullRefund(order.getId());
        int moved = orderMapper.markRefundRollup(order.getId(), GzJpOrderStatus.REFUNDED.getCode());
        log.warn("[gz-jp-refund] ★ GZ-PAY 全额退款旁路同步 order_no={} out_trade_no={} 补标 {} 行 → 订单 refunded(affected={})。"
                + "注意：这条路径不经过拼团行级退款，gz_jp_refund 里没有对应退款单",
            orderNo, outTradeNo, touched, moved);
    }

    // ============================================================
    //  工具
    // ============================================================

    /**
     * 解析提交给微信的退款回调地址。
     *
     * <p>顺序：{@code gz.jp.pay.refund-notify-url} → 从 {@code gz.pay.refund-notify-url} 的 origin 推导
     * → mock 通道下允许为空 → real 通道下 <b>fail-fast</b>。</p>
     *
     * <p><b>为什么 real 下宁可拒绝发起退款也不用空地址</b>：微信不会因为 notify_url 为空就不退钱，
     * 它照样退 —— 但回调永远回不来，那笔单会永远停在「退款中」，
     * 而客人已经收到钱了。对不上账比退不了款更麻烦。</p>
     */
    private String resolveRefundNotifyUrl() {
        String configured = jpPayProperties.getRefundNotifyUrl();
        if (StrUtil.isNotBlank(configured)) {
            return StrUtil.trim(configured);
        }
        String global = payProperties.getRefundNotifyUrl();
        if (StrUtil.isNotBlank(global)) {
            return originOf(global) + JP_REFUND_NOTIFY_PATH;
        }
        if (payProperties.isMock()) {
            // mock 通道压根不连微信，空地址无害；强制配置只会让本地 / 单测起不来
            return "";
        }
        throw new ServiceException("未配置拼团退款回调地址（gz.jp.pay.refund-notify-url 或 "
            + "gz.pay.refund-notify-url），拒绝发起真实退款 —— 否则钱退了但回调回不来，单会永远停在退款中");
    }

    /** 取 URL 的 {@code scheme://host[:port]}；解析不出来就原样返回（调用方拼路径仍能工作）。 */
    private static String originOf(String url) {
        int schemeEnd = url.indexOf("://");
        if (schemeEnd < 0) {
            return url;
        }
        int pathStart = url.indexOf('/', schemeEnd + 3);
        return pathStart < 0 ? url : url.substring(0, pathStart);
    }

    /**
     * 入参 id 规范化：去空 → 去重 → <b>升序</b> → 上限校验。
     *
     * <p>升序是防死锁的关键（106 已踩过口径）：两个并发请求勾了重叠的行，
     * 若加锁顺序相反 InnoDB 直接判死锁。排序后所有请求按同一顺序申请行锁，最坏只是排队。</p>
     */
    private List<Long> normalizeIds(List<Long> raw) {
        if (ObjectUtil.isEmpty(raw)) {
            throw new ServiceException("请至少选择一行商品");
        }
        Set<Long> distinct = new HashSet<>();
        for (Long id : raw) {
            if (id != null) {
                distinct.add(id);
            }
        }
        if (distinct.isEmpty()) {
            throw new ServiceException("请至少选择一行商品");
        }
        if (distinct.size() > BATCH_MAX) {
            throw new ServiceException("单次最多标记 " + BATCH_MAX + " 行，请分批处理");
        }
        List<Long> ids = new ArrayList<>(distinct);
        Collections.sort(ids);
        return ids;
    }

    private Page<GzJpRefundRow> buildPage(PageQuery pageQuery) {
        long current = 1L;
        long size = PAGE_DEFAULT;
        if (pageQuery != null) {
            if (pageQuery.getPageNum() != null && pageQuery.getPageNum() > 0) {
                current = pageQuery.getPageNum();
            }
            if (pageQuery.getPageSize() != null && pageQuery.getPageSize() > 0) {
                size = Math.min(pageQuery.getPageSize(), PAGE_MAX);
            }
        }
        return new Page<>(current, size);
    }

    /**
     * 客人筛选条件 → user_id 集合。
     *
     * @return null = 不按客人筛选；空集合 = 关键词无匹配（调用方短路返回空结果）
     */
    private Collection<Long> resolveUserIds(GzJpRefundQueryBo q) {
        boolean hasKeyword = StrUtil.isNotBlank(q.getKeyword());
        if (q.getUserId() == null && !hasKeyword) {
            return null;
        }
        if (!hasKeyword) {
            return List.of(q.getUserId());
        }
        List<Long> byKeyword = userService.selectIdsByKeyword(StrUtil.trim(q.getKeyword()));
        if (ObjectUtil.isEmpty(byKeyword)) {
            return List.of();
        }
        if (q.getUserId() == null) {
            return byKeyword;
        }
        return byKeyword.contains(q.getUserId()) ? List.of(q.getUserId()) : List.of();
    }

    private Map<Long, GzUserVO> loadUsers(List<GzJpRefundRow> rows) {
        Set<Long> ids = new HashSet<>();
        for (GzJpRefundRow row : rows) {
            if (row.getUserId() != null) {
                ids.add(row.getUserId());
            }
        }
        if (ids.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Long, GzUserVO> map = userService.selectVoMapByIds(ids);
        return map == null ? Collections.emptyMap() : map;
    }

    private static String trunc(String s) {
        if (s == null) {
            return null;
        }
        return s.length() > 480 ? s.substring(0, 480) : s;
    }
}
