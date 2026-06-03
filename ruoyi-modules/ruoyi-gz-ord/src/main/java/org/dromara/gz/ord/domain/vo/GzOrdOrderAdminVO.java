package org.dromara.gz.ord.domain.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * admin 预购订单列表 / 详情 VO（GZ-ORD-105 AC5/AC6，只读）。
 *
 * <p>列表用核心字段（order_no / 用户手机号 / 商品名 / 金额 / 状态 / 时间）；详情额外含三段
 * snapshot 解析 + 物流字段（detail 接口补充，列表不投影 snapshot 区以省带宽）。</p>
 *
 * <p>ID 跨层契约：{@code id} / {@code userId} 用 {@link ToStringSerializer} 转 string。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-ORD-105)
 */
@Data
public class GzOrdOrderAdminVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 订单主键（string） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** 订单业务码 PREORD-yyyyMMdd-6位序号 */
    private String orderNo;

    /** 下单用户 id（string） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long userId;

    /** 下单用户手机号（关联 gz_user 取；未绑定为 null） */
    private String userPhone;

    /** 下单用户昵称（关联 gz_user 取，便于客服核对） */
    private String userNickname;

    /** 商品名（snapshot） */
    private String productName;

    /** 规格名（snapshot） */
    private String specName;

    /** 购买数量 */
    private Integer qty;

    /** 订单总额（分；前端 / 100 显示元） */
    private Long totalAmountCent;

    /** 业务态 created/paid/cancelled/in_logistics/delivered/refunded */
    private String businessStatus;

    /** 业务态中文 label（附录 A.5） */
    private String businessStatusLabel;

    /** 物流态 in_japan/in_china_dispatching/delivered */
    private String logisticsStatus;

    /** 物流态中文 label（附录 A.7） */
    private String logisticsStatusLabel;

    /** 国内快递公司编码（in_china_dispatching 后有值） */
    private String cnCarrierCode;

    /** 国内快递公司中文名（gz_express_carrier 翻译） */
    private String cnCarrierName;

    /** 国内快递单号 */
    private String cnTrackingNo;

    /** 收件人姓名（详情；snapshot.recipient） */
    private String recipient;

    /** 收件人手机号（详情；snapshot.mobile） */
    private String recipientMobile;

    /** 完整收货地址（详情；snapshot province+city+district+detail 拼接） */
    private String fullAddress;

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
