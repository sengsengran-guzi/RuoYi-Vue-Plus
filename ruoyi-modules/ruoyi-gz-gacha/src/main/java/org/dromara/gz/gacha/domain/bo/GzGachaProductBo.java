package org.dromara.gz.gacha.domain.bo;

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
 * 产品库增改业务对象（ADR-0013 / GZ-GACHA-112 admin 端）。
 *
 * <p>validate 分组：{@link AddGroup} 新增 / {@link EditGroup} 编辑。</p>
 *
 * <p><b>受控字段</b>（admin 可填）：name / imageId / referenceValueCent（分，可空）/ ipTag /
 * enabled / remark。<b>系统管理字段</b>：productNo（系统生成）/ version / 公共字段。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-GACHA-112)
 */
@Data
public class GzGachaProductBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键（编辑时必传） */
    @NotNull(message = "产品 ID 不能为空", groups = EditGroup.class)
    private Long id;

    /** 产品名 */
    @NotBlank(message = "产品名不能为空", groups = {AddGroup.class, EditGroup.class})
    @Size(max = 128, message = "产品名长度不能超过 128", groups = {AddGroup.class, EditGroup.class})
    private String name;

    /** 产品图 file_id（→gz_file_object.id，禁裸 url；可空） */
    private Long imageId;

    /** 公示参考价（分，可空）；非空时 ≥0 */
    @Min(value = 0, message = "公示价不能为负", groups = {AddGroup.class, EditGroup.class})
    private Long referenceValueCent;

    /** IP 标签（可空） */
    @Size(max = 64, message = "IP 标签长度不能超过 64", groups = {AddGroup.class, EditGroup.class})
    private String ipTag;

    /** 1 可投放 / 0 停用（不填默认 1） */
    private Integer enabled;

    /** 备注 */
    @Size(max = 500, message = "备注长度不能超过 500", groups = {AddGroup.class, EditGroup.class})
    private String remark;
}
