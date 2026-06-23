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
 * 回收 IP 主数据增改业务对象（GZ-RECYCLE-004 admin 端）。
 *
 * <p>validate 分组：{@link AddGroup} 新增 / {@link EditGroup} 编辑。</p>
 *
 * <p><b>受控字段</b>（admin 可填）：ipName / enabled / sortNo / remark。
 * <b>系统管理字段</b>（不接收前端）：id（编辑回填）/ tenantId / 公共字段（自动填充）。
 * ipName 同租户唯一（DB UNIQUE(tenant_id, ip_name)），service 保存前预检给友好提示。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-RECYCLE-004)
 */
@Data
public class GzRecycleIpBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键（编辑时必传） */
    @NotNull(message = "IP ID 不能为空", groups = EditGroup.class)
    private Long id;

    /** IP / 系列名称（火影 / 海贼王...），同租户唯一 */
    @NotBlank(message = "IP 名称不能为空", groups = {AddGroup.class, EditGroup.class})
    @Size(max = 64, message = "IP 名称长度不能超过 64", groups = {AddGroup.class, EditGroup.class})
    private String ipName;

    /** 启用标志（0=停用 / 1=启用），默认启用 */
    private Integer enabled;

    /** 展示排序（小在前） */
    @Min(value = 0, message = "排序不能为负", groups = {AddGroup.class, EditGroup.class})
    private Integer sortNo;

    /** 备注 */
    @Size(max = 500, message = "备注长度不能超过 500", groups = {AddGroup.class, EditGroup.class})
    private String remark;
}
