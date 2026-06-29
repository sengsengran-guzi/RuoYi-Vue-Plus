package org.dromara.gz.bean.domain.bo;

import io.github.linpeilie.annotations.AutoMapper;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.core.validate.AddGroup;
import org.dromara.common.core.validate.EditGroup;
import org.dromara.common.mybatis.core.domain.BaseEntity;
import org.dromara.gz.bean.domain.entity.GzBeanFreePromo;

import java.io.Serial;
import java.time.LocalDate;

/**
 * gz_bean_free_promo 增改业务对象（GZ-BEAN-025，ADR-0015 §4）。
 *
 * <p>字段口径权威：doc/11 §3.11；validate 分组 {@link AddGroup} / {@link EditGroup}。
 * 跨字段约束（{@code days} 周期必须填 anchorDate、start/end 顺序）在 Service 层校验
 * （单字段注解无法表达条件依赖）。</p>
 *
 * <p><b>受控字段</b>：storeId / periodType / periodDays / anchorDate / freeCount / startDate /
 * endDate / enabled / remark。<b>禁填字段</b>：id（编辑时由 body 传）/ tenantId / 公共字段。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-BEAN-025)
 */
@Data
@EqualsAndHashCode(callSuper = true)
@AutoMapper(target = GzBeanFreePromo.class, reverseConvertGenerate = false)
public class GzBeanFreePromoBo extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键（编辑时必传） */
    @NotNull(message = "促销配置 ID 不能为空", groups = EditGroup.class)
    private Long id;

    /** FK → gz_bean_store.id；每门店一行，新增必填，编辑不可改 */
    @NotNull(message = "门店不能为空", groups = AddGroup.class)
    private Long storeId;

    /** 周期类型 day / week / days */
    @NotNull(message = "周期类型不能为空", groups = {AddGroup.class, EditGroup.class})
    @Pattern(regexp = "^(day|week|days)$", message = "周期类型仅支持 day / week / days",
        groups = {AddGroup.class, EditGroup.class})
    private String periodType;

    /** period_type=days 时的滚动周期天数 N（>=1）；其余类型忽略 */
    @PositiveOrZero(message = "滚动周期天数不能为负", groups = {AddGroup.class, EditGroup.class})
    private Integer periodDays;

    /** days 滚动周期锚点起算日（period_type=days 时 Service 校验必填） */
    private LocalDate anchorDate;

    /** 每周期免费名额 N（>=0） */
    @NotNull(message = "免费名额不能为空", groups = {AddGroup.class, EditGroup.class})
    @PositiveOrZero(message = "免费名额不能为负", groups = {AddGroup.class, EditGroup.class})
    private Integer freeCount;

    /** 促销窗口起（可空=不限） */
    private LocalDate startDate;

    /** 促销窗口止（可空=不限） */
    private LocalDate endDate;

    /** 总开关 0=关 / 1=开 */
    @NotNull(message = "开关状态不能为空", groups = {AddGroup.class, EditGroup.class})
    private Integer enabled;

    /** 备注 */
    @Size(max = 500, message = "备注长度不能超过 500", groups = {AddGroup.class, EditGroup.class})
    private String remark;
}
