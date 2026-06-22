package org.dromara.gz.coupon.domain.bo;

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
 * 券模板增改业务对象（GZ-COUPON-001 admin 端）。
 *
 * <p>字段口径权威：doc/11 §11.1；validate 分组：{@link AddGroup} 新增 / {@link EditGroup} 编辑。</p>
 *
 * <p><b>受控字段</b>（admin 可填）：name / discountType / amountCent / applicableBusiness /
 * validDays / totalQuota / issueStrategy / issueConfigJson / remark。
 * <b>系统管理字段</b>（不接收前端，service 内部赋值）：templateNo（系统生成）/ issuedCount（发券 +N）/
 * status（走 pause/archive/activate 流转，不允许 add/edit 直接改）/ version / tenantId / 公共字段。</p>
 *
 * <p>金额 amountCent 为<b>分</b>（跨层契约 #4，前端元↔分换算在 UI 层）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-COUPON-001)
 */
@Data
public class GzCouponTemplateBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键（编辑时必传） */
    @NotNull(message = "模板 ID 不能为空", groups = EditGroup.class)
    private Long id;

    /** 券名 */
    @NotBlank(message = "券名不能为空", groups = {AddGroup.class, EditGroup.class})
    @Size(max = 64, message = "券名长度不能超过 64", groups = {AddGroup.class, EditGroup.class})
    private String name;

    /** 折扣类型（cash/full_reduce/percent，枚举校验在 service；V1.2 仅 cash） */
    @NotBlank(message = "折扣类型不能为空", groups = {AddGroup.class, EditGroup.class})
    @Size(max = 16, message = "折扣类型长度不能超过 16", groups = {AddGroup.class, EditGroup.class})
    private String discountType;

    /** 代金券固定抵扣额（分，≥ 0） */
    @NotNull(message = "券面额不能为空", groups = {AddGroup.class, EditGroup.class})
    @Min(value = 0, message = "券面额不能为负", groups = {AddGroup.class, EditGroup.class})
    private Long amountCent;

    /** 适用业务（pindou，枚举校验在 service；V1.2 仅 pindou） */
    @NotBlank(message = "适用业务不能为空", groups = {AddGroup.class, EditGroup.class})
    @Size(max = 16, message = "适用业务长度不能超过 16", groups = {AddGroup.class, EditGroup.class})
    private String applicableBusiness;

    /** 领券后有效天数（≥ 1） */
    @NotNull(message = "有效天数不能为空", groups = {AddGroup.class, EditGroup.class})
    @Min(value = 1, message = "有效天数至少为 1", groups = {AddGroup.class, EditGroup.class})
    private Integer validDays;

    /** 模板总发放配额（NULL=不限；填则 ≥ 0） */
    @Min(value = 0, message = "总配额不能为负", groups = {AddGroup.class, EditGroup.class})
    private Integer totalQuota;

    /** 发放策略（manual/filtered/event，枚举校验在 service；仅 manual/filtered 可新建，event 预留） */
    @NotBlank(message = "发放策略不能为空", groups = {AddGroup.class, EditGroup.class})
    @Size(max = 32, message = "发放策略长度不能超过 32", groups = {AddGroup.class, EditGroup.class})
    private String issueStrategy;

    /** 策略参数 JSON（manual 可空；service 校验为合法 JSON） */
    @Size(max = 2048, message = "策略参数长度不能超过 2048", groups = {AddGroup.class, EditGroup.class})
    private String issueConfigJson;

    /** 备注 */
    @Size(max = 500, message = "备注长度不能超过 500", groups = {AddGroup.class, EditGroup.class})
    private String remark;
}
