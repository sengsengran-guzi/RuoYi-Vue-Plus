package org.dromara.gz.jp.service.internal.dto;

import lombok.Data;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.dromara.gz.jp.domain.entity.GzJpRefund;
import org.dromara.gz.jp.domain.enums.GzJpFulfillRejectReason;
import org.dromara.gz.jp.domain.enums.GzJpFulfillStatus;
import org.dromara.gz.jp.domain.enums.GzJpRefundSkipReason;
import org.dromara.gz.jp.domain.vo.GzJpFulfillRejectVO;

import java.util.ArrayList;
import java.util.List;

/**
 * 「标记购买失败 + 建退款单」事务①的<b>执行计划</b>（GZ-JP-107 内部结构，不下发前端）。
 *
 * <p><b>它存在的理由</b>：事务①提交之后才能去调微信（否则受理失败会把失败记录一起回滚掉），
 * 所以事务①必须把「哪几张退款单要提交、哪几行本次没花钱、哪几行被拒」原样带出事务边界，
 * 交给事务外的调用方逐张提交。这是跨事务边界的<b>唯一传递物</b>。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-JP-107)
 */
@Data
public class GzJpRefundPlan {

    /** 本次请求的行数（去重后） */
    private int requested;

    /** 本次真正被置为 purchase_failed 的行数 */
    private int markedFailed;

    /** 本来就已是 purchase_failed 的行数（状态没动，但会检查退款） */
    private int alreadyFailed;

    /** 履约侧逐行拒绝明细 */
    private final List<GzJpFulfillRejectVO> rejects = new ArrayList<>();

    /** ★ 待提交微信的退款单（事务①提交后由调用方逐张调 IWechatPayClient.refund） */
    private final List<PendingRefund> pending = new ArrayList<>();

    /** 零元行：不经过微信、事务①内已直接置已退款 */
    private final List<PendingRefund> immediate = new ArrayList<>();

    /** 本次没有发起新退款的行（之前已退 / 正在退 / 上次失败待人工重试） */
    private final List<SkippedRefund> skipped = new ArrayList<>();

    /** 退款侧被拒的行（缺支付流水 / 会超退）—— 与履约侧拒绝分开，因为状态可能已经标上了 */
    private final List<RefundReject> refundRejects = new ArrayList<>();

    // ============ 记录 ============

    /** 履约侧拒绝一行 */
    public void reject(Long itemId, String currentStatus, GzJpFulfillRejectReason reason) {
        GzJpFulfillRejectVO vo = new GzJpFulfillRejectVO();
        vo.setItemId(itemId);
        vo.setCurrentStatus(currentStatus);
        vo.setCurrentStatusLabel(currentStatus == null ? null : GzJpFulfillStatus.labelOf(currentStatus));
        vo.setReasonCode(reason.name());
        vo.setReason(reason.getMessage());
        rejects.add(vo);
    }

    /** 记一张待提交微信的退款单 */
    public void pending(Long itemId, GzJpRefund refund) {
        pending.add(new PendingRefund(itemId, refund));
    }

    /** 记一张零元、事务内已完成的退款单 */
    public void immediateRefunded(Long itemId, GzJpRefund refund) {
        immediate.add(new PendingRefund(itemId, refund));
    }

    /** 记一行「本次没花钱」及其原因 */
    public void skip(Long itemId, GzJpRefund existing, GzJpRefundSkipReason reason) {
        skipped.add(new SkippedRefund(itemId, existing, reason));
    }

    /** 记一行退款侧被拒（履约状态可能已经标上了，所以与 {@link #reject} 分开） */
    public void rejectRefund(Long itemId, String message, int errorCode) {
        refundRejects.add(new RefundReject(itemId, message, errorCode));
    }

    // ============ 派生 ============

    /** 零元行的 item id（事务①尾部据此做 rollup —— 它们没有回调会来做这件事） */
    public List<Long> getImmediateRefundedItemIds() {
        List<Long> ids = new ArrayList<>(immediate.size());
        for (PendingRefund p : immediate) {
            ids.add(p.getItemId());
        }
        return ids;
    }

    /** 本次没有发起新退款的行数（含退款侧被拒） */
    public int getRefundsSkipped() {
        return skipped.size() + refundRejects.size();
    }

    // ============ 值对象 ============

    /** 一张退款单 + 它对应的商品行 */
    @Getter
    @RequiredArgsConstructor
    public static class PendingRefund {
        private final Long itemId;
        private final GzJpRefund refund;
    }

    /** 一行「本次没花钱」+ 已存在的退款单 + 原因 */
    @Getter
    @RequiredArgsConstructor
    public static class SkippedRefund {
        private final Long itemId;
        private final GzJpRefund existing;
        private final GzJpRefundSkipReason reason;
    }

    /** 一行退款侧被拒 */
    @Getter
    @RequiredArgsConstructor
    public static class RefundReject {
        private final Long itemId;
        private final String message;
        private final int errorCode;
    }
}
