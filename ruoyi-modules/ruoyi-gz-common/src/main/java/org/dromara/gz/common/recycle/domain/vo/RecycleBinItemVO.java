package org.dromara.gz.common.recycle.domain.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 回收站统一行视图（GZ-ADMIN-108）。
 *
 * <p>跨业务表 {@code del_flag='2'} 记录的统一展示结构。删除人 = {@code update_by}（逻辑删时 ruoyi 写入）、
 * 删除时间 = {@code update_time}；{@code daysAgo} = 距今天数（service 算）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-ADMIN-108)
 */
@Data
public class RecycleBinItemVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 实体类型 code（news / ord_product / ...） */
    private String entityType;

    /** 实体类型中文标签 */
    private String entityTypeLabel;

    /** 记录主键（string，避免 JS 精度丢失） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long entityId;

    /** 实体展示名称 */
    private String entityName;

    /** 删除人（update_by → 译名，service 填 username 或 nickName） */
    private String deleteOperatorName;

    /** 删除时间（update_time） */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime deleteTime;

    /** 距今天数（NOW − deleteTime，前端展示用） */
    private Long daysAgo;

    /** 是否已归档（archived_flag=1 → true，已标记待 SnailJob 物理清理） */
    private Boolean archived;
}
