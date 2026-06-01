package org.dromara.gz.common.pay.domain.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import org.dromara.gz.common.pay.domain.entity.GzPayTransaction;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * gz_pay_transaction 视图对象（GZ-PAY-001）。
 *
 * <p>字段权威：doc/11 §4.2。admin 列表 / 详情 + mp 轮询状态共用。</p>
 *
 * <p><b>ID String 化</b>（dongjiaoshan 教训 #1）：id / userId 用 {@link ToStringSerializer}
 * 防 JS Number 精度丢失。{@code amountCent} 保持 Long（前端除以 100 显示元）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-PAY-001)
 */
@Data
@AutoMapper(target = GzPayTransaction.class)
public class GzPayTransactionVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    private String outTradeNo;

    private String businessType;

    private String businessOrderNo;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long userId;

    private String openid;

    private String channelCode;

    /** 金额（分）；前端 amountCent / 100 显示元 */
    private Long amountCent;

    private String currency;

    private Long feeCent;

    private String status;

    private String prepayId;

    private String transactionId;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime paidTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime expireTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime closedTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;

    private String remark;
}
