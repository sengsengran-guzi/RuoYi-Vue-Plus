package org.dromara.gz.ord.domain.vo.applet;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Builder;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * mp 我的订单列表卡 VO（GZ-ORD-105 AC1，GET /app/gz/ord/order/list）。
 *
 * <p>展示数据全部来自 snapshot（决策 D3，不查商品表，防下架/改名/调价后历史订单异常）。
 * 主图 {@code mainImageUrl} 由后端用 snapshot 的 main_image_id 换签名 URL（图片用 image_id，强约束 #9）。</p>
 *
 * <p>ID 跨层契约：{@code id} 用 {@link ToStringSerializer} 转 string（跳详情用 id）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-ORD-105)
 */
@Data
@Builder
public class OrdOrderListItemVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 订单主键（string；点击跳详情携 id） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** 订单业务码 PREORD-yyyyMMdd-6位序号 */
    private String orderNo;

    /** 商品名（snapshot） */
    private String productName;

    /** 商品主图可访问 URL（snapshot main_image_id 换签名；解析失败回退占位图） */
    private String productImageUrl;

    /** 规格名（snapshot） */
    private String specName;

    /** 购买数量 */
    private Integer qty;

    /** 订单总额（分；前端 / 100 显示元） */
    private Long totalAmountCent;

    /** 业务态 created/paid/cancelled/in_logistics/delivered/refunded（附录 A.5） */
    private String businessStatus;

    /** 物流态 in_japan/in_china_dispatching/delivered（附录 A.7；与 business_status 并行） */
    private String logisticsStatus;

    /** 用户视角统一 chip code（all/to_pay/to_ship/shipping/done/cancelled/refunded，doc/11 §8.2） */
    private String chipStatus;

    /** 统一 chip 中文 label（待支付/待发货/运输中/已完成/已取消/已退款，doc/11 §8.2） */
    private String chipLabel;

    /** 下单时间 */
    private LocalDateTime createTime;
}
