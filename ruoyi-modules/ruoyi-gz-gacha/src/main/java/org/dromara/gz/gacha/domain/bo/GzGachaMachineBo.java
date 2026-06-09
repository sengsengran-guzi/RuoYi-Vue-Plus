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
import java.time.LocalDateTime;

/**
 * 扭蛋机增改业务对象（GZ-GACHA-101 admin 端）。
 *
 * <p>字段口径权威：doc/11 §7.1。validate 分组：{@link AddGroup} 新增 / {@link EditGroup} 编辑。</p>
 *
 * <p><b>受控字段</b>（admin 可填）：name / coverImageId / singlePriceCent（分，≥0）/ tenPackPriceCent
 * （分，可空=不支持十连）/ ipTag / onlineTime / offlineTime / remark。<b>系统管理字段</b>：machineNo
 * （系统生成）/ status（走 changeStatus 流转，不允许直接改；新增固定 off_shelf）/ salesCount / version /
 * 公共字段。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-GACHA-101)
 */
@Data
public class GzGachaMachineBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键（编辑时必传） */
    @NotNull(message = "扭蛋机 ID 不能为空", groups = EditGroup.class)
    private Long id;

    /** 机器名 */
    @NotBlank(message = "机器名不能为空", groups = {AddGroup.class, EditGroup.class})
    @Size(max = 128, message = "机器名长度不能超过 128", groups = {AddGroup.class, EditGroup.class})
    private String name;

    /** 封面 file_id（→gz_file_object.id，禁裸 url；可空） */
    private Long coverImageId;

    /** 单抽价（分）— 禁 _fen；≥ 0 */
    @NotNull(message = "单抽价不能为空", groups = {AddGroup.class, EditGroup.class})
    @Min(value = 0, message = "单抽价不能为负", groups = {AddGroup.class, EditGroup.class})
    private Long singlePriceCent;

    /** 十连价（分，可空=不支持十连）；非空时 ≥ 0 */
    @Min(value = 0, message = "十连价不能为负", groups = {AddGroup.class, EditGroup.class})
    private Long tenPackPriceCent;

    /** IP 标签（可空） */
    @Size(max = 64, message = "IP 标签长度不能超过 64", groups = {AddGroup.class, EditGroup.class})
    private String ipTag;

    /** 上架时间（可空） */
    private LocalDateTime onlineTime;

    /** 计划下架时间（可空；到点 GACHA-104/cron 自动转 auto_off） */
    private LocalDateTime offlineTime;

    /** 备注 */
    @Size(max = 500, message = "备注长度不能超过 500", groups = {AddGroup.class, EditGroup.class})
    private String remark;
}
