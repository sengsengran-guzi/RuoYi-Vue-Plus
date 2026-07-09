package org.dromara.gz.recycle.domain.bo;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.dromara.common.core.validate.AddGroup;
import org.dromara.common.core.validate.EditGroup;

import java.io.Serial;
import java.io.Serializable;

/**
 * 回收数量桶 + 时长增改业务对象（GZ-RECYCLE-004 admin 端）。
 *
 * <p>validate 分组：{@link AddGroup} 新增 / {@link EditGroup} 编辑。</p>
 *
 * <p><b>受控字段</b>（admin 可填）：code / label / durationMinutes / enabled / sortNo / remark。
 * <b>系统管理字段</b>（不接收前端）：id（编辑回填）/ tenantId / 公共字段（自动填充）。
 * code 同租户唯一（DB UNIQUE(tenant_id, code)），service 保存前预检给友好提示。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-RECYCLE-004)
 */
@Data
public class GzRecycleQtyRangeBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键（编辑时必传） */
    @NotNull(message = "数量桶 ID 不能为空", groups = EditGroup.class)
    private Long id;

    /** 桶机读码（mp 提交落 qtyBucketCode），同租户唯一 */
    @NotBlank(message = "桶编码不能为空", groups = {AddGroup.class, EditGroup.class})
    @Size(max = 32, message = "桶编码长度不能超过 32", groups = {AddGroup.class, EditGroup.class})
    private String code;

    /** 桶展示文案（如 1-25 件） */
    @NotBlank(message = "桶展示文案不能为空", groups = {AddGroup.class, EditGroup.class})
    @Size(max = 64, message = "桶展示文案长度不能超过 64", groups = {AddGroup.class, EditGroup.class})
    private String label;

    /** 该桶预计回收时长（分钟，≥ 0） */
    @NotNull(message = "预计回收时长不能为空", groups = {AddGroup.class, EditGroup.class})
    @Min(value = 0, message = "预计回收时长不能为负", groups = {AddGroup.class, EditGroup.class})
    private Integer durationMinutes;

    /** 大单占位：1=选此档下单额外整格占用下一个到店时段（仅最高档）；0=普通档（GZ-RECYCLE-007，默认 0） */
    private Integer occupyNextSlot;

    /** 启用标志（0=停用 / 1=启用），默认启用 */
    private Integer enabled;

    /** 展示排序（小在前） */
    @Min(value = 0, message = "排序不能为负", groups = {AddGroup.class, EditGroup.class})
    private Integer sortNo;

    /** 备注 */
    @Size(max = 500, message = "备注长度不能超过 500", groups = {AddGroup.class, EditGroup.class})
    private String remark;
}
