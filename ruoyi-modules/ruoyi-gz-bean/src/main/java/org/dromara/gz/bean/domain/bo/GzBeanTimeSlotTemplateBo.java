package org.dromara.gz.bean.domain.bo;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.github.linpeilie.annotations.AutoMapper;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.core.validate.AddGroup;
import org.dromara.common.core.validate.EditGroup;
import org.dromara.common.mybatis.core.domain.BaseEntity;
import org.dromara.gz.bean.domain.entity.GzBeanTimeSlotTemplate;

import java.io.Serial;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * gz_bean_time_slot_template 增改 BO（GZ-BEAN-002）。
 *
 * <p>字段口径权威：doc/11 §3.2。</p>
 *
 * <p>{@code weekdays} 校验：逗号分隔的 1-7 字符（如 "1,2,3,4,5"）；
 * service 层进一步校验 endTime &gt; startTime 与重叠规则。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-BEAN-002)
 */
@Data
@EqualsAndHashCode(callSuper = true)
@AutoMapper(target = GzBeanTimeSlotTemplate.class, reverseConvertGenerate = false)
public class GzBeanTimeSlotTemplateBo extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键（编辑必传） */
    @NotNull(message = "时段模板 ID 不能为空", groups = EditGroup.class)
    private Long id;

    /** 门店 ID（新增必填；编辑忽略 — 不允许跨门店搬迁） */
    @NotNull(message = "门店 ID 不能为空", groups = AddGroup.class)
    private Long storeId;

    /** 时段名（可空，空时 mp 端用 start_time-end_time） */
    @Size(max = 32, message = "时段名长度不能超过 32", groups = {AddGroup.class, EditGroup.class})
    private String slotName;

    /** 开始时间 */
    @NotNull(message = "开始时间不能为空", groups = {AddGroup.class, EditGroup.class})
    @JsonFormat(pattern = "HH:mm:ss")
    private LocalTime startTime;

    /** 结束时间（service 层校验 &gt; start） */
    @NotNull(message = "结束时间不能为空", groups = {AddGroup.class, EditGroup.class})
    @JsonFormat(pattern = "HH:mm:ss")
    private LocalTime endTime;

    /** 逗号分隔 ISO 星期，1-7 */
    @NotBlank(message = "适用星期不能为空", groups = {AddGroup.class, EditGroup.class})
    @Pattern(regexp = "^[1-7](,[1-7])*$",
        message = "适用星期格式错误，应为逗号分隔的 1-7（如 1,2,3,4,5）",
        groups = {AddGroup.class, EditGroup.class})
    private String weekdays;

    /** 生效日（可空） */
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate effectiveDate;

    /** 失效日（可空） */
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate expireDate;

    /** 0=停用 / 1=启用 */
    @Min(value = 0, message = "enabled 取值仅 0/1", groups = {AddGroup.class, EditGroup.class})
    @Max(value = 1, message = "enabled 取值仅 0/1", groups = {AddGroup.class, EditGroup.class})
    private Integer enabled;

    /** 排序值 */
    private Integer sortNo;

    /** 备注 */
    @Size(max = 500, message = "备注长度不能超过 500", groups = {AddGroup.class, EditGroup.class})
    private String remark;
}
