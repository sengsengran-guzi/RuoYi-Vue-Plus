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
 * 奖品增改业务对象（GZ-GACHA-101 admin 端，奖品池嵌在机器详情下）。
 *
 * <p>字段口径权威：doc/11 §7.2。validate 分组：{@link AddGroup} 新增 / {@link EditGroup} 编辑。</p>
 *
 * <p><b>受控字段</b>（admin 可填）：machineId（新增必传，归属机器）/ name / imageId / rarity（SSR/SR/R/N，
 * service 层校验）/ weight（≥0）/ stockInitial（≥0）/ stockRemain（≥0 且 ≤stockInitial，service 校验）/
 * referenceValueCent（分，可空）/ enabled / remark。<b>系统管理字段</b>：prizeNo（系统生成）/ version /
 * 公共字段。</p>
 *
 * <p><b>库存语义</b>：stockRemain 在配置态由 admin 填（盘点/上新）；并发扣减是 GACHA-104 的
 * SELECT FOR UPDATE，本卡 service 仅拦「配置态负库存 / remain>initial」（R2）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-GACHA-101)
 */
@Data
public class GzGachaPrizeBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键（编辑时必传） */
    @NotNull(message = "奖品 ID 不能为空", groups = EditGroup.class)
    private Long id;

    /** 归属扭蛋机 id（新增必传；编辑不可改归属，忽略） */
    @NotNull(message = "归属扭蛋机不能为空", groups = AddGroup.class)
    private Long machineId;

    /** 奖品名 */
    @NotBlank(message = "奖品名不能为空", groups = {AddGroup.class, EditGroup.class})
    @Size(max = 128, message = "奖品名长度不能超过 128", groups = {AddGroup.class, EditGroup.class})
    private String name;

    /** 奖品图 file_id（→gz_file_object.id，禁裸 url；可空） */
    private Long imageId;

    /** 稀有度 SSR/SR/R/N（service 层校验枚举合法，强约束 #2） */
    @NotBlank(message = "稀有度不能为空", groups = {AddGroup.class, EditGroup.class})
    @Size(max = 8, message = "稀有度长度不能超过 8", groups = {AddGroup.class, EditGroup.class})
    private String rarity;

    /** 概率权重整数（≥0；归一化运行时重算，决策 D3） */
    @NotNull(message = "权重不能为空", groups = {AddGroup.class, EditGroup.class})
    @Min(value = 0, message = "权重不能为负", groups = {AddGroup.class, EditGroup.class})
    private Integer weight;

    /** 初始库存（≥0） */
    @NotNull(message = "初始库存不能为空", groups = {AddGroup.class, EditGroup.class})
    @Min(value = 0, message = "初始库存不能为负", groups = {AddGroup.class, EditGroup.class})
    private Integer stockInitial;

    /** 剩余库存（≥0 且 ≤stockInitial，service 校验；可空→新增时默认 = stockInitial） */
    @Min(value = 0, message = "剩余库存不能为负", groups = {AddGroup.class, EditGroup.class})
    private Integer stockRemain;

    /** 公示参考价（分，可空）；非空时 ≥0 */
    @Min(value = 0, message = "公示价不能为负", groups = {AddGroup.class, EditGroup.class})
    private Long referenceValueCent;

    /** 0临时下架/1参与抽奖（不填默认 1） */
    private Integer enabled;

    /** 备注 */
    @Size(max = 500, message = "备注长度不能超过 500", groups = {AddGroup.class, EditGroup.class})
    private String remark;
}
