package org.dromara.gz.bean.domain.bo;

import io.github.linpeilie.annotations.AutoMapper;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.core.validate.AddGroup;
import org.dromara.common.core.validate.EditGroup;
import org.dromara.common.mybatis.core.domain.BaseEntity;
import org.dromara.gz.bean.domain.entity.GzBeanSeatTypeConfig;

import java.io.Serial;

/**
 * gz_bean_seat_type_config 增改 BO（GZ-BEAN-013）。
 *
 * <p>字段口径权威：doc/11 §3.4；validate 分组：
 * {@link AddGroup} 新增 / {@link EditGroup} 编辑。</p>
 *
 * <p><b>受控字段</b>：storeId / seatType / quantity / priceCent / enabled / sortNo / remark。
 * <b>禁填</b>：id（编辑必传） / tenantId / 公共字段。</p>
 *
 * <p>seatType UNIQUE(tenant_id, store_id, seat_type) 由 DB + Service 兜底；
 * 且 seatType 必属 single/double/quad（{@code @Pattern} 正则校验 + Service Set 兜底；
 * 不用 @DictPattern —— 其 validator 查系统字典 tenant_id='000000' 在业务租户 1001 上下文失败，见 ServiceImpl 注释）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-BEAN-013)
 */
@Data
@EqualsAndHashCode(callSuper = true)
@AutoMapper(target = GzBeanSeatTypeConfig.class, reverseConvertGenerate = false)
public class GzBeanSeatTypeConfigBo extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键（编辑必传） */
    @NotNull(message = "配置 ID 不能为空", groups = EditGroup.class)
    private Long id;

    /** 门店 ID（新增必填；编辑忽略 — 不允许跨门店搬迁） */
    @NotNull(message = "门店 ID 不能为空", groups = AddGroup.class)
    private Long storeId;

    /** 自定义显示名（取代字典；新增/编辑必填；同店不重名 — service + DB uk 兜底）。ADR-0014 §1 去字典化 */
    @NotBlank(message = "类型名称不能为空", groups = {AddGroup.class, EditGroup.class})
    @Size(max = 32, message = "类型名称长度不能超过 32", groups = {AddGroup.class, EditGroup.class})
    private String name;

    /** 订法 whole=整桌 / seat=按座（必填）。ADR-0014 §2 */
    @NotBlank(message = "订法不能为空", groups = {AddGroup.class, EditGroup.class})
    @Pattern(regexp = "^(whole|seat)$",
        message = "订法无效（应为 whole 整桌 / seat 按座 之一）",
        groups = {AddGroup.class, EditGroup.class})
    private String bookMode;

    /** 每桌座位数（≥1；seat 模式 quantity*capacity = 每格总座数） */
    @NotNull(message = "每桌座位数不能为空", groups = {AddGroup.class, EditGroup.class})
    @Min(value = 1, message = "每桌座位数不能小于 1", groups = {AddGroup.class, EditGroup.class})
    private Integer capacity;

    /** 数量（每格物理单位数 = 桌/单位数），≥ 0 */
    @NotNull(message = "数量不能为空", groups = {AddGroup.class, EditGroup.class})
    @Min(value = 0, message = "数量不能小于 0", groups = {AddGroup.class, EditGroup.class})
    private Integer quantity;

    /** 单价（分），≥ 0 */
    @NotNull(message = "单价不能为空", groups = {AddGroup.class, EditGroup.class})
    @Min(value = 0, message = "单价不能小于 0", groups = {AddGroup.class, EditGroup.class})
    private Long priceCent;

    /** 0=停用 / 1=启用 */
    @Min(value = 0, message = "enabled 取值仅 0/1", groups = {AddGroup.class, EditGroup.class})
    private Integer enabled;

    /** 排序值 */
    private Integer sortNo;

    /** 备注 */
    @Size(max = 500, message = "备注长度不能超过 500", groups = {AddGroup.class, EditGroup.class})
    private String remark;
}
