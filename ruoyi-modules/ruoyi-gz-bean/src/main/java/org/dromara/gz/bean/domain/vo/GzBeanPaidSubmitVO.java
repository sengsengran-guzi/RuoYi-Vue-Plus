package org.dromara.gz.bean.domain.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Builder;
import lombok.Data;
import org.dromara.gz.common.pay.domain.vo.MpPayParamsVO;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * mp 端付费预约提交响应 VO（GZ-BEAN-014）。
 *
 * <p>下单事务成功后返回：booking 基本信息 + 实付金额 + 支付五参（实付>0 时）/ 免费单标记（实付=0）。</p>
 *
 * <ul>
 *   <li>实付 &gt; 0：{@code free=false}，{@code payParams} 含 mp {@code wx.requestPayment} 五参，mp 拉起支付</li>
 *   <li>实付 = 0（免费单兜底）：{@code free=true}，{@code payParams=null}，pay_status 已直接 paid，mp 跳详情</li>
 * </ul>
 *
 * @author kevin-coder (sensenran-guzi · GZ-BEAN-014)
 */
@Data
@Builder
public class GzBeanPaidSubmitVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    private String bookingNo;

    /** 座位类型 value（single/double/quad） */
    private String seatType;

    /** 座位类型中文名快照 */
    private String seatTypeSnapshot;

    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate sessDate;

    @JsonFormat(pattern = "HH:mm:ss")
    private LocalTime slotStart;

    @JsonFormat(pattern = "HH:mm:ss")
    private LocalTime slotEnd;

    /** 本笔金额（分）= 该座位类型单价 */
    private Long amountCent;

    /** 优惠券抵扣额（分）；本卡恒 0（券逻辑 D13） */
    private Long discountAmountCent;

    /** 实付（分）= amountCent − discountAmountCent（下限 0） */
    private Long payAmountCent;

    /** 付费状态机当前态：实付>0 → paying；免费单 → paid */
    private String payStatus;

    /** 是否免费单（实付=0，无需拉起支付） */
    private Boolean free;

    /** 支付单业务码（实付>0 时；免费单 null） */
    private String outTradeNo;

    /** mp 拉起支付五参（实付>0 时；免费单 null） */
    private MpPayParamsVO payParams;
}
