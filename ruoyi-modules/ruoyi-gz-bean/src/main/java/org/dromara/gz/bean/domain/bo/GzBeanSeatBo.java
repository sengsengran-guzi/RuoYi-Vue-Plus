package org.dromara.gz.bean.domain.bo;

import io.github.linpeilie.annotations.AutoMapper;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.core.validate.AddGroup;
import org.dromara.common.core.validate.EditGroup;
import org.dromara.common.mybatis.core.domain.BaseEntity;
import org.dromara.gz.bean.domain.entity.GzBeanSeat;

import java.io.Serial;

/**
 * gz_bean_seat 座位单元增改 BO（ADR-0015）。
 *
 * <p>字段口径权威：doc/11 §3.3；validate 分组：
 * {@link AddGroup} 新增 / {@link EditGroup} 编辑。</p>
 *
 * <p><b>受控字段</b>：storeId / seatTypeConfigId / seatNo / tableNo / zone / rowLabel /
 * colIndex / enabled / sortNo / remark。<b>禁填</b>：id（编辑必传） / tenantId / 公共字段。</p>
 *
 * <p>新增必填 seatTypeConfigId（座位单元必须挂桌型）；seatNo UNIQUE(tenant_id, store_id, seat_no)
 * 已由 DB + Service 兜底，撞号友好报错。编辑禁改 storeId / seatNo（业务码 / 归属稳定）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-BEAN-023)
 */
@Data
@EqualsAndHashCode(callSuper = true)
@AutoMapper(target = GzBeanSeat.class, reverseConvertGenerate = false)
public class GzBeanSeatBo extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键（编辑必传） */
    @NotNull(message = "座位 ID 不能为空", groups = EditGroup.class)
    private Long id;

    /** 门店 ID（新增必填；编辑忽略 — 不允许跨门店搬迁） */
    @NotNull(message = "门店 ID 不能为空", groups = AddGroup.class)
    private Long storeId;

    /** 所属桌型 config ID（新增必填 — 座位单元必须挂桌型；编辑可改归属桌型） */
    @NotNull(message = "所属桌型不能为空", groups = AddGroup.class)
    private Long seatTypeConfigId;

    /** 座位/桌编号（新增必填；编辑忽略 — 业务码不可改） */
    @NotBlank(message = "座位号不能为空", groups = AddGroup.class)
    @Size(max = 16, message = "座位号长度不能超过 16", groups = {AddGroup.class, EditGroup.class})
    private String seatNo;

    /** 同桌聚合标识（seat 模式同桌多座聚成一组）；可空 */
    @Size(max = 16, message = "同桌聚合标识长度不能超过 16", groups = {AddGroup.class, EditGroup.class})
    private String tableNo;

    /** 分区标签（影院图分区渲染）；可空 */
    @Size(max = 16, message = "分区标签长度不能超过 16", groups = {AddGroup.class, EditGroup.class})
    private String zone;

    /** 行标 */
    @Size(max = 8, message = "行标长度不能超过 8", groups = {AddGroup.class, EditGroup.class})
    private String rowLabel;

    /** 列序号 */
    @Min(value = 1, message = "列序号不能小于 1", groups = {AddGroup.class, EditGroup.class})
    @Max(value = 99, message = "列序号不能大于 99", groups = {AddGroup.class, EditGroup.class})
    private Integer colIndex;

    /** 0=停用 / 1=启用 */
    @Min(value = 0, message = "enabled 取值仅 0/1", groups = {AddGroup.class, EditGroup.class})
    @Max(value = 1, message = "enabled 取值仅 0/1", groups = {AddGroup.class, EditGroup.class})
    private Integer enabled;

    /** 排序值 */
    private Integer sortNo;

    /** 备注 */
    @Size(max = 500, message = "备注长度不能超过 500", groups = {AddGroup.class, EditGroup.class})
    private String remark;
}
