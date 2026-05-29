package org.dromara.gz.bean.domain.bo;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 批量生成座位 BO（GZ-BEAN-002 AC 3）。
 *
 * <p>按编号自动生成 N 个座位（如 prefix=A，startIndex=1，count=6 → A1 / A2 / ... / A6）。</p>
 *
 * <p>UNIQUE(tenant_id, store_id, seat_no) 已存在则跳过该编号（service 层逐个 INSERT IGNORE 语义）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-BEAN-002)
 */
@Data
public class GzBeanSeatBatchGenerateBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 门店 ID */
    @NotNull(message = "门店 ID 不能为空")
    private Long storeId;

    /** 前缀（如 "A" / "B"），可空，空时直接用数字编号 */
    @Size(max = 8, message = "前缀长度不能超过 8")
    private String prefix;

    /** 起始序号（默认 1） */
    @NotNull(message = "起始序号不能为空")
    @Min(value = 1, message = "起始序号不能小于 1")
    @Max(value = 999, message = "起始序号不能大于 999")
    private Integer startIndex;

    /** 生成数量（1-30） */
    @NotNull(message = "生成数量不能为空")
    @Min(value = 1, message = "生成数量至少 1")
    @Max(value = 30, message = "生成数量至多 30")
    private Integer count;

    /** 行标（如 "A"） — 可空，用于辅助网格 */
    @Size(max = 8, message = "行标长度不能超过 8")
    private String rowLabel;

    /** 起始列序号（如 1） — 可空 */
    @Min(value = 1, message = "起始列序号不能小于 1")
    @Max(value = 99, message = "起始列序号不能大于 99")
    private Integer startColIndex;
}
