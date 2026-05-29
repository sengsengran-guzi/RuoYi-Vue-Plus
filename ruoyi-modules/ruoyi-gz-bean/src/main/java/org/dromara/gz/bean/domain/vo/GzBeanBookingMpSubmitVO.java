package org.dromara.gz.bean.domain.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Builder;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * mp 端预约提交成功响应 VO（GZ-BEAN-004）。
 *
 * <p>含核销码 verifyCode + qrPayload — mp 端在 success 页用 qrPayload 直接渲染二维码。</p>
 *
 * <p><b>qrPayload 格式</b>（doc/11 §3.6）：{@code "BK|{bookingNo}|{verifyCode}"} —
 * 短码长度约 50 字符，QR 码不会过密影响识别率。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-BEAN-004)
 */
@Data
@Builder
public class GzBeanBookingMpSubmitVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    private String bookingNo;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long seatId;

    private String seatNoSnapshot;

    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate sessDate;

    @JsonFormat(pattern = "HH:mm:ss")
    private LocalTime slotStart;

    @JsonFormat(pattern = "HH:mm:ss")
    private LocalTime slotEnd;

    /** 核销码（mp 端展示给用户 / 内嵌 QR） */
    private String verifyCode;

    /** QR payload 字符串（mp 端直接用此字符串渲染二维码） */
    private String qrPayload;
}
