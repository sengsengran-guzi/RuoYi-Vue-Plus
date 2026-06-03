package org.dromara.gz.common.pay.domain.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import org.dromara.gz.common.pay.domain.entity.GzPayRefund;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * gz_pay_refund 视图对象（GZ-PAY-103）。
 *
 * <p>字段权威：doc/11 §4.4。admin 退款记录列表 / 详情用。</p>
 *
 * <p><b>ID String 化</b>（跨层契约铁律 #1）：id 用 {@link ToStringSerializer} 防 JS Number 精度丢失。
 * 用户可见的"号" = refund_no（业务码，跨层契约铁律 #2），不暴露内部 id。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-PAY-103)
 */
@Data
@AutoMapper(target = GzPayRefund.class)
public class GzPayRefundVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    private String refundNo;

    private String transactionId;

    private String outTradeNo;

    private String wechatRefundId;

    /** 退款金额（分）；前端 refundAmountCent / 100 显示元 */
    private Long refundAmountCent;

    private String reason;

    private String status;

    private String triggeredBy;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime triggeredTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime refundedTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;

    private String remark;
}
