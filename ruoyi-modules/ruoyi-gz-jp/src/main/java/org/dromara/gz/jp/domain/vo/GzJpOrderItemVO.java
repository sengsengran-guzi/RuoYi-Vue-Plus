package org.dromara.gz.jp.domain.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 订单商品行 VO（GZ-JP-105；mp 订单详情 UI:mp.order_detail.item_row 消费）。
 *
 * <p><b>全部字段来自下单快照 + 行自身状态</b>，不回查商品表 ——
 * 商品改名 / 下架 / 删除后，订单详情仍显示下单那一刻的样子。
 * 唯一的例外是 {@link #mainImageUrl}（快照存的是 file id，读时换 1h 预签名 URL）。</p>
 *
 * <p><b>★ 无「包裹」概念</b>（REQ-FULFILL-006）：只有 {@link #trackingNo}。
 * mp 详情页按状态 + 运单号分组渲染，同一单号只显示一次，页面上不出现「包裹」二字。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-JP-105)
 */
@Data
public class GzJpOrderItemVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 订单行主键（string）—— GZ-JP-106/107 批量推进 / 标记失败传的就是它 */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** 商品主键（string，快照值；商品可能已被删，点进去要能兜住 404） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long productId;

    /** 商品编号 JPP-yyyyMMdd-6位（快照值） */
    private String productNo;

    /** 商品名称（快照值） */
    private String name;

    /** 主图可渲染 URL（快照 file id 换的 1h 预签名；无图 / 解析失败回落占位图，绝不给 null） */
    private String mainImageUrl;

    /** 预计到货时间文本（快照值，如「8月下旬」） */
    private String deliveryDateText;

    /** ★ 额外注意事项（快照值，客人下单时接受的条款） */
    private String noticeText;

    /** 所属场名称（快照值；★ 一单可跨多场，详情按需分组） */
    private String eventName;

    /** 数量 */
    private Integer qty;

    /** 下单时单价（分） */
    private Long unitPriceCent;

    /** 行金额（分）= 单价 × 数量。★ 行级退款按此金额退 */
    private Long amountCent;

    /** 来源（batch=拼团；二期代切 snap） */
    private String source;

    /** 履约状态 code（字典 gz_jp_fulfill_status） */
    private String fulfillStatus;

    /** 履约状态中文（后端给，前端别自己维护映射） */
    private String fulfillStatusLabel;

    /** 国内快递编码（字典 gz_express_carrier；未发货为 null） */
    private String carrierCode;

    /** 国内运单号（未发货为 null）—— 同单号的行属同一批，mp 按它合并显示 */
    private String trackingNo;

    /** 发货时间（未发货为 null） */
    private LocalDateTime shippedAt;

    /** 行级退款状态 code（仅购买失败行有值） */
    private String refundStatus;

    /** 行级退款状态中文（仅购买失败行有值） */
    private String refundStatusLabel;

    /** 已退金额（分，仅已退款行有值） */
    private Long refundAmountCent;
}
