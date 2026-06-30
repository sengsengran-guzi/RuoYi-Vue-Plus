package org.dromara.gz.bean.domain.bo;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.core.validate.AddGroup;
import org.dromara.common.core.validate.EditGroup;
import org.dromara.common.mybatis.core.domain.BaseEntity;

import java.io.Serial;
import java.time.LocalTime;
import java.util.List;

/**
 * gz_bean_seat_closure 增改业务对象（GZ-BEAN-036，Req3）。
 *
 * <p><b>批量新增（AddGroup）</b>：一次提交 {@code seatIds[] × weekdays[]} 笛卡尔展开成 N 行
 * （同 {@code timeStart / timeEnd}）。如选 3 座 × 2 星期 → 建 6 行关闭规则。</p>
 *
 * <p><b>单条编辑（EditGroup）</b>：按 {@code id} 改 {@code enabled} / {@code timeStart} / {@code timeEnd}
 * （storeId / seatId / weekday 归属稳定，编辑不可改）。</p>
 *
 * <p>跨字段约束（timeStart &lt; timeEnd）在 Service 层校验（单字段注解无法表达）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-BEAN-036)
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class GzBeanSeatClosureBo extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键（编辑时必传） */
    @NotNull(message = "关闭规则 ID 不能为空", groups = EditGroup.class)
    private Long id;

    /** FK → gz_bean_store.id；批量新增必填，编辑不可改 */
    @NotNull(message = "门店不能为空", groups = AddGroup.class)
    private Long storeId;

    /** 批量新增：被关闭的具体座位 id 列表（与 weekdays 笛卡尔展开成 N 行） */
    @NotEmpty(message = "请至少选择一个座位", groups = AddGroup.class)
    private List<Long> seatIds;

    /** 批量新增：ISO 星期列表 1=Mon..7=Sun（与 seatIds 笛卡尔展开成 N 行） */
    @NotEmpty(message = "请至少选择一个星期", groups = AddGroup.class)
    private List<Integer> weekdays;

    /** 关闭时段起（含），[timeStart, timeEnd)；新增 + 编辑均必填 */
    @NotNull(message = "关闭时段起不能为空", groups = {AddGroup.class, EditGroup.class})
    @JsonFormat(pattern = "HH:mm")
    private LocalTime timeStart;

    /** 关闭时段止（不含）；新增 + 编辑均必填 */
    @NotNull(message = "关闭时段止不能为空", groups = {AddGroup.class, EditGroup.class})
    @JsonFormat(pattern = "HH:mm")
    private LocalTime timeEnd;

    /** 单条编辑：0=停用 / 1=生效（编辑必填；新增统一置 1 由 service 兜底） */
    @NotNull(message = "启用状态不能为空", groups = EditGroup.class)
    @Min(value = 0, message = "启用状态非法", groups = EditGroup.class)
    @Max(value = 1, message = "启用状态非法", groups = EditGroup.class)
    private Integer enabled;

    /** 备注 */
    @Size(max = 500, message = "备注长度不能超过 500", groups = {AddGroup.class, EditGroup.class})
    private String remark;
}
