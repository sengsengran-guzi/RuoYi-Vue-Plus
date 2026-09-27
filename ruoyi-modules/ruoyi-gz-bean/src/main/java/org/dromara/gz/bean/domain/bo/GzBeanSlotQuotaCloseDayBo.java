package org.dromara.gz.bean.domain.bo;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDate;

/**
 * 看板「今日可售」批量配额关闭业务对象（ADR-0024 §3）。
 *
 * <p>语义：把该桌型该日期<b>每一个小时格</b>的 {@code close_count} 统一覆盖为 {@code closeCount}
 * （覆盖不累加）。{@code closeCount=0} = 恢复全开；{@code closeCount = slotCapacity(config)} = 「今天不上小程序」。</p>
 *
 * <p>上限不在 Bo 层钉死（{@code slotCapacity} 要读 config 才知道）—— 由 service 层按
 * {@code 0 ≤ closeCount ≤ slotCapacity} 校验，报错带上 cap 实际值。</p>
 *
 * @author kevin-coder (sensenran-guzi · ADR-0024 §3)
 */
@Data
public class GzBeanSlotQuotaCloseDayBo implements Serializable {

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

    /** 该日每格统一关闭的配额个数（0 = 恢复全开；上限 = slotCapacity，service 层校验） */
    @NotNull(message = "关闭数不能为空")
    @Min(value = 0, message = "关闭数不能为负")
    private Integer closeCount;

    /** 备注（整批统一写） */
    @Size(max = 500, message = "备注长度不能超过 500")
    private String remark;
}
