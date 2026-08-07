package org.dromara.gz.jp.domain.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 退款单列表行（GZ-JP-107 → GZ-JP-108 admin「拼团退款」页消费）。
 *
 * <p><b>这张页面存在的唯一理由是「哪几笔钱没退成功」</b>（AC：退款失败要 admin 可见，不静默吞）。
 * 所以后端已把 {@code refund_failed} 排在最前，前端<b>不要再按时间倒序覆盖掉这个排序</b>。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-JP-107)
 */
@Data
public class GzJpRefundAdminVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 退款单 id（string）—— 重试接口传的就是它 */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** 商户退款单号 JPRF-yyyyMMdd-6位（= 微信 out_refund_no） */
    private String refundNo;

    /** 订单 id（string） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long orderId;

    /** 订单号 JPO-yyyyMMdd-6位 */
    private String orderNo;

    /** 订单当前状态 code（paid / partial_refunded / refunded） */
    private String businessStatus;

    /** 订单状态中文 */
    private String businessStatusLabel;

    /** 商品行 id（string）—— 退的是这一行 */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long orderItemId;

    /** 客人 id（string） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long userId;

    /** 客人昵称 */
    private String userNickname;

    /** 客人手机号 */
    private String userMobile;

    /** 客人编号 */
    private String userNo;

    /** 原业务支付订单号（微信按它定位原单） */
    private String outTradeNo;

    /** ★ 本次退款金额（分）= 该行 amount_cent */
    private Long refundAmountCent;

    /** 原支付单总额（分）—— 与退款额不等即说明这是部分退款 */
    private Long totalAmountCent;

    /** 微信退款单号（受理 / 回调回填；未受理为 null） */
    private String wechatRefundId;

    /** 退款单状态 code：refunding / refunded / refund_failed */
    private String status;

    /** 退款单状态中文 */
    private String statusLabel;

    /** 退款原因（提交给微信的，客人可见） */
    private String reason;

    /** ★ 失败原因（status=refund_failed 时必有）—— 直接显示在列表里，别藏进详情 */
    private String failReason;

    /** 向微信提交的次数（含重试）；次数高且仍非终态 = 需要人工介入 */
    private Integer attemptCount;

    /** 触发人 */
    private String triggeredBy;

    /** 发起时间 */
    private LocalDateTime triggeredTime;

    /** 退款成功时间（未成功为 null） */
    private LocalDateTime refundedTime;

    /** 是否允许「重新发起退款」（后端算好，前端别自己判状态） */
    private Boolean retryable;
}
