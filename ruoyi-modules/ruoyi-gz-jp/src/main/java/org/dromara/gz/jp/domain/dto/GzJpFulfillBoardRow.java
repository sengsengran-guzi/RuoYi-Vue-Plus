package org.dromara.gz.jp.domain.dto;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 履约看板查询的<b>原始行</b>（GZ-JP-106）—— {@code gz_jp_order_item} join {@code gz_jp_order} 的投影。
 *
 * <p>只是 mapper 的落地容器，不直接下发；服务层再补客人信息 / 快照解包 / 中文文案后转
 * {@code GzJpFulfillBoardItemVO}。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-JP-106)
 */
@Data
public class GzJpFulfillBoardRow implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;
    private Long orderId;
    private Long userId;
    private Long productId;
    private String productSnapshotJson;
    private Integer qty;
    private Long unitPriceCent;
    private Long amountCent;
    private String fulfillStatus;
    private String carrierCode;
    private String trackingNo;
    private LocalDateTime shippedAt;
    private String refundStatus;
    private Long refundAmountCent;

    /** 来自 gz_jp_order */
    private String orderNo;
    private String businessStatus;
    private LocalDateTime paidTime;
    private LocalDateTime orderCreateTime;
}
