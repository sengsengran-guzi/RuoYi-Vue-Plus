package org.dromara.gz.ord.domain.vo.applet;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Builder;
import lombok.Data;
import org.dromara.gz.common.pay.domain.vo.MpPayParamsVO;

import java.io.Serial;
import java.io.Serializable;

/**
 * mp 预购下单返回（GZ-ORD-104 AC2）。
 *
 * <p>下单事务成功后返回订单 id（mp 跳 pay-result 用）+ order_no + 微信 5 参签名（mp 调起
 * {@code uni.requestPayment}）。pay-result 页用 {@code ordOrderId} 拉详情轮询状态。</p>
 *
 * <p>ID 跨层契约：{@code ordOrderId} 用 {@link ToStringSerializer} 转 string。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-ORD-104)
 */
@Data
@Builder
public class SubmitOrderVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 预购订单主键（string；mp 跳 pay-result?orderId= 用） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long ordOrderId;

    /** 订单业务码 PREORD-yyyyMMdd-6位序号 */
    private String orderNo;

    /** 微信 jsapi 5 参签名（mp uni.requestPayment 入参，PAY-101 返回） */
    private MpPayParamsVO payParams;
}
