package org.dromara.gz.bean.domain.bo;

import io.github.linpeilie.annotations.AutoMapper;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.core.validate.AddGroup;
import org.dromara.common.core.validate.EditGroup;
import org.dromara.common.mybatis.core.domain.BaseEntity;
import org.dromara.gz.bean.domain.entity.GzBeanStore;

import java.io.Serial;
import java.math.BigDecimal;

/**
 * gz_bean_store 增改业务对象（GZ-BEAN-001）。
 *
 * <p>字段口径权威：doc/11 §3.1；validate 分组：
 * {@link AddGroup} 新增 / {@link EditGroup} 编辑。</p>
 *
 * <p><b>受控字段</b>（admin 端可填）：storeNo / name / type / address / phone / businessHours /
 * status / maxAdvanceDays / longitude / latitude / remark。
 * <b>禁填字段</b>（系统管理）：id（编辑时由 path 注入）/ tenantId / 公共字段。</p>
 *
 * <p>枚举校验在 Service 层做（doc/11 §3.1 type / status 枚举值）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-BEAN-001)
 */
@Data
@EqualsAndHashCode(callSuper = true)
@AutoMapper(target = GzBeanStore.class, reverseConvertGenerate = false)
public class GzBeanStoreBo extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键（编辑时必传） */
    @NotNull(message = "门店 ID 不能为空", groups = EditGroup.class)
    private Long id;

    /** 业务码（如 CD001） — 新增必填，编辑不可改 */
    @NotBlank(message = "业务码不能为空", groups = AddGroup.class)
    @Size(max = 32, message = "业务码长度不能超过 32", groups = {AddGroup.class, EditGroup.class})
    private String storeNo;

    /** 门店名 */
    @NotBlank(message = "门店名不能为空", groups = {AddGroup.class, EditGroup.class})
    @Size(max = 64, message = "门店名长度不能超过 64", groups = {AddGroup.class, EditGroup.class})
    private String name;

    /** 类型 pindou / guzi — 不填默认 pindou */
    @Pattern(regexp = "^(pindou|guzi)$", message = "门店类型仅支持 pindou / guzi",
        groups = {AddGroup.class, EditGroup.class})
    private String type;

    /** 完整地址 */
    @NotBlank(message = "地址不能为空", groups = {AddGroup.class, EditGroup.class})
    @Size(max = 255, message = "地址长度不能超过 255", groups = {AddGroup.class, EditGroup.class})
    private String address;

    /** 经度（V1.0 可空） */
    private BigDecimal longitude;

    /** 纬度（V1.0 可空） */
    private BigDecimal latitude;

    /** 门店电话 */
    @Size(max = 20, message = "电话长度不能超过 20", groups = {AddGroup.class, EditGroup.class})
    private String phone;

    /** 营业时间字符串（V1.0 人肉字符串） */
    @Size(max = 255, message = "营业时间长度不能超过 255", groups = {AddGroup.class, EditGroup.class})
    private String businessHours;

    /** 门店图片（gz_file_object.id，可空；admin 上传，mp 无图不显示） */
    private Long imageId;

    /** 状态 open / closed / maintenance — 不填默认 open */
    @Pattern(regexp = "^(open|closed|maintenance)$", message = "门店状态仅支持 open / closed / maintenance",
        groups = {AddGroup.class, EditGroup.class})
    private String status;

    /** 可预约最大提前天数（默认 14） */
    private Integer maxAdvanceDays;

    /** 备注（覆盖 BaseEntity 缺失的 remark；admin 表单可填） */
    @Size(max = 500, message = "备注长度不能超过 500", groups = {AddGroup.class, EditGroup.class})
    private String remark;
}
