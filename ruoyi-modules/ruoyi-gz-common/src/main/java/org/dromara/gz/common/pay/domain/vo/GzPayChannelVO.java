package org.dromara.gz.common.pay.domain.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import org.dromara.gz.common.pay.domain.entity.GzPayChannel;

import java.io.Serial;
import java.io.Serializable;

/**
 * gz_pay_channel 视图对象（GZ-PAY-001，admin 只读展示）。
 *
 * <p>字段权威：doc/11 §4.1。</p>
 *
 * <p><b>敏感字段脱敏</b>（强约束 #4）：{@code mchId} 由 service 层脱敏后再放入此 VO；
 * {@code apiV3KeyRef} / {@code mchCertSerial} 仅展示引用名/序列号本身（非明文密钥，本就不在 DB）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-PAY-001)
 */
@Data
@AutoMapper(target = GzPayChannel.class)
public class GzPayChannelVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    private String channelCode;

    private String displayName;

    private String appid;

    /** 商户号（service 层脱敏后填充，如 16********88） */
    private String mchId;

    private String apiV3KeyRef;

    private String mchCertSerial;

    private String notifyUrl;

    private String refundNotifyUrl;

    private Integer enabled;

    private String remark;
}
