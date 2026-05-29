package org.dromara.gz.bean.domain.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import org.dromara.gz.bean.domain.entity.GzBeanBooking;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * gz_bean_booking 视图对象（GZ-BEAN-004）。
 *
 * <p>字段权威：doc/11 §3.4。admin / mp 共用。</p>
 *
 * <p><b>ID 类型 String 化</b>（dongjiaoshan 教训 #1）：id / userId / storeId / seatId 用
 * {@link ToStringSerializer} 序列化为 string，防 JS Number 精度丢失。</p>
 *
 * <p><b>不暴露</b>：verifyCode（核销码，敏感）/ dedupToken（内部去重 token）/ openid。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-BEAN-004)
 */
@Data
@AutoMapper(target = GzBeanBooking.class)
public class GzBeanBookingVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    private String bookingNo;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long userId;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long storeId;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long seatId;

    private String seatNoSnapshot;

    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate sessDate;

    @JsonFormat(pattern = "HH:mm:ss")
    private LocalTime slotStart;

    @JsonFormat(pattern = "HH:mm:ss")
    private LocalTime slotEnd;

    /** 手机号（admin 看全 / mp 详情脱敏由前端处理） */
    private String mobileSnapshot;

    private String status;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime verifyTime;

    private String verifiedBy;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime cancelledTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime noShowTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;

    private String remark;
}
