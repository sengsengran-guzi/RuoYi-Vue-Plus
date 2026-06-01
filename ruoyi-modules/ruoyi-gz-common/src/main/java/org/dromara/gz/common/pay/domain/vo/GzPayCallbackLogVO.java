package org.dromara.gz.common.pay.domain.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import org.dromara.gz.common.pay.domain.entity.GzPayCallbackLog;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * gz_pay_callback_log 视图对象（GZ-PAY-001，admin 订单详情内回调日志展示）。
 *
 * <p>字段权威：doc/11 §4.3。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-PAY-001)
 */
@Data
@AutoMapper(target = GzPayCallbackLog.class)
public class GzPayCallbackLogVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    private String transactionId;

    private String outTradeNo;

    private String callbackType;

    private String rawBody;

    private String processStatus;

    private String processError;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;
}
