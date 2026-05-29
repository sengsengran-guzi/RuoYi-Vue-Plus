package org.dromara.gz.bean.domain.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import org.dromara.gz.bean.domain.entity.GzBeanTimeSlotTemplate;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * gz_bean_time_slot_template 视图对象（GZ-BEAN-002）。
 *
 * <p>字段权威：doc/11 §3.2。admin / mp 共用。
 * mp 端 BEAN-003 拉时段时仅取 enabled=1 + weekdays 匹配的子集。</p>
 *
 * <p>{@code startTime} / {@code endTime} 序列化为 "HH:mm:ss"（不带毫秒，前端易渲染）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-BEAN-002)
 */
@Data
@AutoMapper(target = GzBeanTimeSlotTemplate.class)
public class GzBeanTimeSlotTemplateVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;
    private Long storeId;
    private String slotName;

    @JsonFormat(pattern = "HH:mm:ss")
    private LocalTime startTime;

    @JsonFormat(pattern = "HH:mm:ss")
    private LocalTime endTime;

    private String weekdays;

    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate effectiveDate;

    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate expireDate;

    private Integer enabled;
    private Integer sortNo;
    private LocalDateTime createTime;
    private String remark;
}
