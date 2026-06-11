package org.dromara.gz.common.recycle.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 回收站原始行（GZ-ADMIN-108，mapper 泛型查询出参）。
 *
 * <p>各业务表 {@code del_flag='2'} 行的统一裸结构（service 再补 entityType/label/operatorName/daysAgo）。
 * 列经 mapUnderscoreToCamelCase 映射（{@code ${nameColumn} AS entity_name} 等）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-ADMIN-108)
 */
@Data
public class RecycleRawRow implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 记录主键 */
    private Long id;

    /** 展示名称（来自各表 nameColumn） */
    private String entityName;

    /** 删除人 user_id（update_by） */
    private Long deleteOperatorId;

    /** 删除时间（update_time） */
    private LocalDateTime deleteTime;

    /** 归档标记（0 / 1） */
    private Integer archivedFlag;
}
