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
 * 回收价目表增改业务对象（GZ-RECYCLE-001 admin 端）。
 *
 * <p>字段口径权威：doc/11 §12.1；validate 分组：{@link AddGroup} 新增 / {@link EditGroup} 编辑。</p>
 *
 * <p><b>受控字段</b>（admin 可填）：category / qtyMin / qtyMax / unitPriceCent / durationMinutes /
 * enabled / sortNo / remark。<b>系统管理字段</b>（不接收前端）：id（系统/编辑回填）/ tenantId / 公共字段。</p>
 *
 * <p>区间下界/上界 + 单价为<b>分</b>（跨层契约 #4，前端元↔分换算在 UI 层）。
 * {@code qtyMax} 为 null = 无上界；{@code qtyMin <= qtyMax} 与「同品类区间不重叠」均在 service 校验
 * （bean validation 无法跨字段比较 + 跨行校验）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-RECYCLE-001)
 */
@Data
public class GzRecyclePriceRuleBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键（编辑时必传） */
    @NotNull(message = "价目规则 ID 不能为空", groups = EditGroup.class)
    private Long id;

    /** 回收品类（字典 gz_recycle_category 值，枚举校验在 service） */
    @NotBlank(message = "回收品类不能为空", groups = {AddGroup.class, EditGroup.class})
    @Size(max = 32, message = "回收品类长度不能超过 32", groups = {AddGroup.class, EditGroup.class})
    private String category;

    /** 数量区间下界（含，≥ 1） */
    @NotNull(message = "数量区间下界不能为空", groups = {AddGroup.class, EditGroup.class})
    @Min(value = 1, message = "数量区间下界至少为 1", groups = {AddGroup.class, EditGroup.class})
    private Integer qtyMin;

    /** 数量区间上界（含；NULL=无上界，填则 ≥ qtyMin 由 service 校验） */
    @Min(value = 1, message = "数量区间上界至少为 1", groups = {AddGroup.class, EditGroup.class})
    private Integer qtyMax;

    /** 单价（分，≥ 0） */
    @NotNull(message = "单价不能为空", groups = {AddGroup.class, EditGroup.class})
    @Min(value = 0, message = "单价不能为负", groups = {AddGroup.class, EditGroup.class})
    private Long unitPriceCent;

    /** 匹配时长（分钟，≥ 0） */
    @NotNull(message = "匹配时长不能为空", groups = {AddGroup.class, EditGroup.class})
    @Min(value = 0, message = "匹配时长不能为负", groups = {AddGroup.class, EditGroup.class})
    private Integer durationMinutes;

    /** 启用标志（0=停用 / 1=启用），默认启用 */
    private Integer enabled;

    /** 同品类内排序 */
    @Min(value = 0, message = "排序不能为负", groups = {AddGroup.class, EditGroup.class})
    private Integer sortNo;

    /** 备注 */
    @Size(max = 500, message = "备注长度不能超过 500", groups = {AddGroup.class, EditGroup.class})
    private String remark;
}
