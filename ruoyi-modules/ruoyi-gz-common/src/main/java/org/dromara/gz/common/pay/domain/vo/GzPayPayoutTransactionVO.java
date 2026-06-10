package org.dromara.gz.common.pay.domain.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import org.dromara.gz.common.pay.domain.entity.GzPayPayoutTransaction;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * gz_pay_payout_transaction 视图对象（GZ-PAY-105）。
 *
 * <p>字段权威：doc/11 §4.8。admin 反向打款单列表 / 详情共用。</p>
 *
 * <p><b>ID String 化</b>（跨层契约 #1）：id / userId 用 {@link ToStringSerializer} 防 JS Number
 * 精度丢失。{@code amountCent} 保持 Long（前端除以 100 显示元）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-PAY-105)
 */
@Data
@AutoMapper(target = GzPayPayoutTransaction.class)
public class GzPayPayoutTransactionVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    private String outPayoutNo;

    private String businessType;

    private String businessOrderNo;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long userId;

    private String receiverOpenid;

    /** 金额（分）；前端 amountCent / 100 显示元 */
    private Long amountCent;

    private String status;

    private String payoutId;

    private String batchId;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime transferredTime;

    private String failReason;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;

    private String remark;
}
