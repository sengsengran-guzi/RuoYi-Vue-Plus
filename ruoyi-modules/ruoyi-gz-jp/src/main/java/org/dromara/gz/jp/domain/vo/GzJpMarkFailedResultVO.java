package org.dromara.gz.jp.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 批量标记购买失败 + 行级退款的执行结果（GZ-JP-107，FLOW:F-JP-04）。
 *
 * <p><b>刻意分成两组计数</b>：履约侧（这些行标没标上）与退款侧（这些行的钱退没退）是
 * <b>两条独立的轴</b>。合成一个「成功 N 行」会掩盖最危险的一种情况 ——
 * <b>状态标成功了但退款失败了</b>（客人看到「购买失败」却没收到钱）。
 * 所以 {@link #markedFailed} 与 {@link #refundsAccepted} 必须分开看，前端也要分开显示。</p>
 *
 * <p><b>部分成功语义</b>（同 106）：能标的标、能退的退，其余逐行给原因。
 * 只有「一行都没标上、也没有一行需要补退款」才抛 4111。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-JP-107)
 */
@Data
public class GzJpMarkFailedResultVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    // ============ 履约侧：这些行标没标上「购买失败」 ============

    /** 本次请求的行数（去重后） */
    private int requested;

    /** 本次真正被置为 purchase_failed 的行数 */
    private int markedFailed;

    /**
     * 本来就已经是 purchase_failed 的行数（状态没动）。
     *
     * <p>不算失败：它们仍会被检查「有没有退过款」，缺退款单就补建
     * —— 覆盖「有人先用 /advance 标了失败但没走退款」的历史行。</p>
     */
    private int alreadyFailed;

    /** 履约侧被拒的行数（= {@link #rejects} 的长度） */
    private int rejected;

    /** 履约侧逐行拒绝原因（复用 106 的 {@code GzJpFulfillRejectReason}） */
    private List<GzJpFulfillRejectVO> rejects = new ArrayList<>();

    // ============ 退款侧：这些行的钱退没退 ============

    /** 本次新建的退款单数 */
    private int refundsCreated;

    /** 其中被微信受理成功的（等异步回调定终态） */
    private int refundsAccepted;

    /** ★ 其中受理被拒的（已落 refund_failed + failReason，admin 可见可重试） */
    private int refundsFailed;

    /** 本次没有发起新退款的行数（之前已退 / 正在退 / 上次失败待人工重试） */
    private int refundsSkipped;

    /** 本次发起的退款总金额（分）= Σ 各行 amount_cent */
    private long refundAmountCentTotal;

    /** 逐行退款明细（含跳过与失败原因） */
    private List<GzJpRefundLineVO> refunds = new ArrayList<>();
}
