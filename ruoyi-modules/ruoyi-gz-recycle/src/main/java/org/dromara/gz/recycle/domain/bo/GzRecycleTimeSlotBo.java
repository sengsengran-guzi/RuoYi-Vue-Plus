package org.dromara.gz.recycle.domain.bo;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.dromara.common.core.validate.AddGroup;
import org.dromara.common.core.validate.EditGroup;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalTime;

/**
 * 回收到店时段增改业务对象（GZ-RECYCLE-006 admin 端）。
 *
 * <p>validate 分组：{@link AddGroup} 新增 / {@link EditGroup} 编辑。</p>
 *
 * <p><b>受控字段</b>（admin 可填）：storeId / label / startTime / endTime / enabled / sortNo / remark。
 * <b>系统管理字段</b>（不接收前端）：id（编辑回填）/ tenantId / 公共字段（自动填充）。
 * (store_id, start_time, end_time) 同租户唯一（DB UNIQUE 兜底，service 预检给友好提示）；
 * end &gt; start 由 service 校验。时间格式 "HH:mm:ss"（Element Plus time-picker value-format 对齐）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-RECYCLE-006)
 */
@Data
public class GzRecycleTimeSlotBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键（编辑时必传） */
    @NotNull(message = "时段 ID 不能为空", groups = EditGroup.class)
    private Long id;

    /** 所属门店（gz_bean_store.id） */
    @NotNull(message = "门店不能为空", groups = {AddGroup.class, EditGroup.class})
    private Long storeId;

    /** 时段展示名（可空；空时 mp 用 "HH:mm-HH:mm" 自动拼） */
    @Size(max = 64, message = "时段名长度不能超过 64", groups = {AddGroup.class, EditGroup.class})
    private String label;

    /** 到店时段开始（HH:mm:ss） */
    @NotNull(message = "开始时间不能为空", groups = {AddGroup.class, EditGroup.class})
    @JsonFormat(pattern = "HH:mm:ss")
    private LocalTime startTime;

    /** 到店时段结束（HH:mm:ss，须晚于开始时间） */
    @NotNull(message = "结束时间不能为空", groups = {AddGroup.class, EditGroup.class})
    @JsonFormat(pattern = "HH:mm:ss")
    private LocalTime endTime;

    /** 启用标志（0=停用 / 1=启用），默认启用 */
    private Integer enabled;

    /** 展示排序（小在前） */
    @Min(value = 0, message = "排序不能为负", groups = {AddGroup.class, EditGroup.class})
    private Integer sortNo;

    /** 备注 */
    @Size(max = 500, message = "备注长度不能超过 500", groups = {AddGroup.class, EditGroup.class})
    private String remark;
}
