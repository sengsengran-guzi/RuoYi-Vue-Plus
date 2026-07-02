package org.dromara.gz.common.pay.domain.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import org.dromara.gz.common.pay.domain.entity.GzPayShippingOrder;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * gz_pay_shipping_order 视图对象 —— admin「发货上报管理」列表。
 *
 * <p>展示微信订单中心发货信息上报任务的状态，供 owner 排查「订单未接入 / 待发货」并手动补报。
 * id String 化防 JS Number 精度丢失（同 {@link GzPayTransactionVO}）。</p>
 *
 * @author kevin-coder (sensenran-guzi)
 */
@Data
@AutoMapper(target = GzPayShippingOrder.class)
public class GzPayShippingOrderVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** 微信支付单号 */
    private String transactionId;

    /** 业务订单号 */
    private String outTradeNo;

    /** preorder / gacha / pindou / test */
    private String businessType;

    /** 支付用户 openid */
    private String openid;

    /** 物流模式 1实体/2同城/3虚拟商品/4自提 */
    private Integer logisticsType;

    /** 商品描述 */
    private String itemDesc;

    /** 支付成功时间 */
    private LocalDateTime paidTime;

    /** 上报状态 pending / success / failed */
    private String uploadStatus;

    /** 上报尝试次数 */
    private Integer attemptCount;

    /** 最近一次失败原因 */
    private String lastError;

    /** 上报成功时间 */
    private LocalDateTime uploadedTime;

    /** 创建时间 */
    private LocalDateTime createTime;
}
