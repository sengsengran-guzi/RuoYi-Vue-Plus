package org.dromara.gz.ord.domain.vo.applet;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Builder;
import lombok.Data;
import org.dromara.gz.ord.domain.dto.OrdSnapshot;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * mp 预购订单详情 VO（GZ-ORD-104 AC5，GET /app/gz/ord/order/{id}）。
 *
 * <p>供 pay-result 轮询 + 订单详情展示。展示数据全部来自三段 snapshot（下单瞬间锁定，
 * 不反查 gz_ord_product/sku/address —— 商品下架/改名/调价后仍显原貌，决策 D3）。ORD-105 会扩 list。</p>
 *
 * <p>ID 跨层契约：{@code id} 用 {@link ToStringSerializer} 转 string。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-ORD-104)
 */
@Data
@Builder
public class OrdOrderDetailVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 订单主键（string） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** 订单业务码 PREORD-yyyyMMdd-6位序号 */
    private String orderNo;

    /** 购买数量 */
    private Integer qty;

    /** 订单总额（分） */
    private Long totalAmountCent;

    /** 业务态 created/paid/cancelled/in_logistics/delivered/refunded（附录 A.5） */
    private String businessStatus;

    /** 业务态中文 label（附录 A.5） */
    private String businessStatusLabel;

    /** 物流态 in_japan/in_china_dispatching/delivered（附录 A.7；C1 2 态+终态） */
    private String logisticsStatus;

    /** 物流态中文 label（附录 A.7） */
    private String logisticsStatusLabel;

    /** 用户视角统一 chip code（all/to_pay/to_ship/shipping/done/cancelled/refunded，doc/11 §8.2，ORD-105 扩） */
    private String chipStatus;

    /** 统一 chip 中文 label（doc/11 §8.2，ORD-105 扩） */
    private String chipLabel;

    /** 国内快递公司中文名（cn_carrier_code 经 gz_express_carrier 翻译；无单号则 null，ORD-105 扩） */
    private String cnCarrierName;

    /** 状态时间线（4 节点，无 closed，ORD-105 扩；cancelled/refunded 详情单独显示不渲染时间线） */
    private List<OrdTimelineNodeVO> timeline;

    /** 商品 snapshot（下单锁定） */
    private OrdSnapshot.Product product;

    /** SKU snapshot（下单锁定） */
    private OrdSnapshot.Sku sku;

    /** 地址 snapshot（下单锁定，不含 receiver_id） */
    private OrdSnapshot.Address address;

    /** 国内快递公司编码（in_china_dispatching 后有值；本卡建字段不写值） */
    private String cnCarrierCode;

    /** 国内快递单号（in_china_dispatching 后有值；本卡建字段不写值） */
    private String cnTrackingNo;

    /** 用户下单备注 */
    private String userNote;

    /** 支付时间 */
    private LocalDateTime paidTime;

    /** 签收时间 */
    private LocalDateTime deliveredTime;

    /** 取消时间 */
    private LocalDateTime cancelledTime;

    /** 下单时间 */
    private LocalDateTime createTime;
}
