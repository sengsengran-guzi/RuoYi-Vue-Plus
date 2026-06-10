package org.dromara.gz.common.pay.enums;

/**
 * 反向打款状态枚举（GZ-PAY-105，doc/11 附录 A.16 / §4.8 / ADR-0006）。
 *
 * <p><b>独立于正向收款 {@link PayStatus}，不复用</b>：反向打款是「店家付钱给用户」（资金出账），
 * 状态语义与收款的 paid/refunded 不同，混用必然语义漂移（ADR-0006 §4 Rationale）。</p>
 *
 * <p>状态机（ADR-0006 §4）：{@code created}（建出账单）→ {@code processing}（已受理，商家转账 API 成功返回）
 * → {@code success}（查单/回调确认到账，写 transferred_time）；旁路 {@code failed}（受理/查单失败，可重试，
 * 重置 created）/ {@code cancelled}（未受理前取消）。<b>主动查单优先</b>（SnailJob 周期扫 processing 态查单）。</p>
 *
 * <p>用常量而非 enum 是为了与 DB VARCHAR 存值、mapper 内条件 UPDATE 的字符串字面量保持单一真源
 * （与 {@link PayStatus} 同款，避免 enum.name() 与 SQL 字面量两处漂移）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-PAY-105)
 */
public final class PayoutStatus {

    /** 单已建、未发起（店员触发打款、落库） */
    public static final String CREATED = "created";
    /** 已发起、待微信处理（transferToUserWallet 受理成功返回） */
    public static final String PROCESSING = "processing";
    /** 转账成功（终态，查单/回调命中 SUCCESS，写 transferred_time） */
    public static final String SUCCESS = "success";
    /** 转账失败（旁路终态，查单/回调命中 FAIL；可重试重置 created） */
    public static final String FAILED = "failed";
    /** 已撤单（旁路终态，未受理前取消） */
    public static final String CANCELLED = "cancelled";

    private PayoutStatus() {
    }
}
