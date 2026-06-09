package org.dromara.gz.ord.domain.bo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 批量上下架入参（GZ-ADMIN-101 AC 7）。{@code ids} 接收 string（跨层契约 #1，Jackson 自动转 Long）；
 * {@code status} 仅 on_shelf / off_shelf（service 兜底校验 auto_off 拒绝）。
 *
 * @author kevin-coder (sensenran-guzi · GZ-ADMIN-101)
 */
@Data
public class GzOrdProductBatchStatusBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 商品 id 列表（前端传 string，Jackson 转 Long） */
    @NotEmpty(message = "请至少选择一个商品")
    private List<Long> ids;

    /** 目标态（on_shelf / off_shelf） */
    @NotBlank(message = "目标状态不能为空")
    private String status;
}
