package org.dromara.gz.common.pay.enums;

/**
 * 支付订单状态枚举（doc/11 附录 A.2 / doc/10 §2 状态机）。
 *
 * <p>V1.0 用前 6 态（created / pending / paid / timeout / closed / failed）；
 * V1.1 GZ-PAY-103 退款服务加 refunding / refunded 两态（paid → refunding → refunded，
 * 受理失败回滚 paid，doc/11 §4.2）。用常量而非 enum 是为了与 DB VARCHAR 存值、mapper 内
 * 条件 UPDATE 的字符串字面量保持单一真源，避免 enum.name() 与 SQL 字面量两处漂移。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-PAY-001)
 */
public final class PayStatus {

    /** 测试单已创建 */
    public static final String CREATED = "created";
    /** 待支付（已拿 prepay_id） */
    public static final String PENDING = "pending";
    /** 已支付（回调验签成功） */
    public static final String PAID = "paid";
    /** 支付超时（5min 未回调，cron 关单） */
    public static final String TIMEOUT = "timeout";
    /** 支付关闭（admin 主动关单） */
    public static final String CLOSED = "closed";
    /** 通道失败（统一下单微信侧错误） */
    public static final String FAILED = "failed";
    /** 退款中（GZ-PAY-103：已发起退款，等微信退款回调，doc/11 §4.2） */
    public static final String REFUNDING = "refunding";
    /** 已退款（GZ-PAY-103：退款回调 SUCCESS，doc/10 §6.N10） */
    public static final String REFUNDED = "refunded";

    private PayStatus() {
    }
}
