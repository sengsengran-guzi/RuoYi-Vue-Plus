package org.dromara.gz.bean.domain.bo;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * gz_bean_slot_quota_close upsert 业务对象（客户 0702 反馈 #4a）。
 *
 * <p>唯一键（tenant/store/config/date/slot）命中即改 close_count（覆盖，不累加），否则新建。
 * {@code closeCount=0} 合法（表示放开该格关闭）；service 走自动 tenant 填充。</p>
 *
 * @author kevin-coder (sensenran-guzi · 客户 0702 反馈 #4a)
 */
@Data
public class GzBeanSlotQuotaCloseBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 门店 id（必填） */
    @NotNull(message = "门店不能为空")
    private Long storeId;

    /** 桌型档 id（必填） */
    @NotNull(message = "桌型不能为空")
    private Long seatTypeConfigId;

    /** 服务日（必填） */
    @NotNull(message = "服务日不能为空")
    @JsonFormat(pattern = "yyyy-MM-dd")
    @DateTimeFormat(pattern = "yyyy-MM-dd")
    private LocalDate sessDate;

    /** 1h 格起整点（必填） */
    @NotNull(message = "时段不能为空")
    @JsonFormat(pattern = "HH:mm")
    @DateTimeFormat(pattern = "HH:mm")
    private LocalTime slotStart;

    /** 该格关闭配额个数（0..999；0 表示放开该格） */
    @NotNull(message = "关闭数不能为空")
    @Min(value = 0, message = "关闭数不能为负")
    @Max(value = 999, message = "关闭数过大")
    private Integer closeCount;

    /** 备注 */
    @Size(max = 500, message = "备注长度不能超过 500")
    private String remark;
}
