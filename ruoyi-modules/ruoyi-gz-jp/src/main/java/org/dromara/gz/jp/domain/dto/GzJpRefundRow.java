package org.dromara.gz.jp.domain.dto;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 退款单列表查询的<b>原始行</b>（GZ-JP-107）—— {@code gz_jp_refund} left join {@code gz_jp_order} 的投影。
 *
 * <p>只是 mapper 的落地容器，不直接下发；服务层再补客人信息 / 中文文案后转
 * {@code GzJpRefundAdminVO}。跟 {@link GzJpFulfillBoardRow} 同款分工。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-JP-107)
 */
@Data
public class GzJpRefundRow implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;
    private String refundNo;
    private Long orderId;
    private Long orderItemId;
    private Long userId;
    private String outTradeNo;
    private Long refundAmountCent;
    private Long totalAmountCent;
    private String wechatRefundId;
    private String status;
    private String reason;
    private String failReason;
    private Integer attemptCount;
    private String triggeredBy;
    private LocalDateTime triggeredTime;
    private LocalDateTime refundedTime;

    /** 来自 gz_jp_order */
    private String orderNo;
    private String businessStatus;
}
