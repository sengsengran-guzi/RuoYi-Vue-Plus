package org.dromara.gz.bean.domain.bo;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * 按周批量配置时段 BO（GZ-BEAN-002 AC 5 / R3 风险缓解）。
 *
 * <p>一次性为某门店在指定 weekdays 上插入多条时段模板，避免逐条新建的 UI 成本。</p>
 *
 * <p>语义：每条 {@code slots} item 在 {@code weekdays} 全集上各 INSERT 1 条。
 * 例：weekdays="1,2,3,4,5"（工作日），slots=[{14:00-15:30 上午}, {16:00-17:30 下午}]，
 * 结果 INSERT 2 条时段模板（每条 weekdays="1,2,3,4,5"）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-BEAN-002)
 */
@Data
public class GzBeanTimeSlotBatchByWeekBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 门店 ID */
    @NotNull(message = "门店 ID 不能为空")
    private Long storeId;

    /** 逗号分隔星期，如 "1,2,3,4,5" */
    @Pattern(regexp = "^[1-7](,[1-7])*$",
        message = "适用星期格式错误，应为逗号分隔的 1-7（如 1,2,3,4,5）")
    @NotNull(message = "适用星期不能为空")
    private String weekdays;

    /** 生效日（可空） */
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate effectiveDate;

    /** 失效日（可空） */
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate expireDate;

    /** 时段列表（≥ 1 条） */
    @NotEmpty(message = "时段列表不能为空")
    @Valid
    private List<SlotItem> slots;

    /** 单条时段项 */
    @Data
    public static class SlotItem implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;

        @Size(max = 32, message = "时段名长度不能超过 32")
        private String slotName;

        @NotNull(message = "开始时间不能为空")
        @JsonFormat(pattern = "HH:mm:ss")
        private LocalTime startTime;

        @NotNull(message = "结束时间不能为空")
        @JsonFormat(pattern = "HH:mm:ss")
        private LocalTime endTime;
    }
}
