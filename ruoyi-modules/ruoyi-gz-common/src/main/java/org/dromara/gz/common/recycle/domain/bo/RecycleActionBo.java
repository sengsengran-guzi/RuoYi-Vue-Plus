package org.dromara.gz.common.recycle.domain.bo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 回收站恢复/归档操作入参（GZ-ADMIN-108）。
 *
 * @author kevin-coder (sensenran-guzi · GZ-ADMIN-108)
 */
@Data
public class RecycleActionBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 实体类型（news / ord_product / ...，须为注册类型） */
    @NotBlank(message = "实体类型不能为空")
    private String entityType;

    /** 记录主键 */
    @NotNull(message = "记录 id 不能为空")
    private Long entityId;
}
