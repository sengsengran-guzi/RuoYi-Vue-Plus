package org.dromara.gz.jp.domain.bo;

import com.fasterxml.jackson.annotation.JsonFormat;
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
 * 场增改业务对象（GZ-JP-101 admin 端，FLOW:F-JP-01.step1）。
 *
 * <p>字段口径唯一真源：{@code doc/jp/authority/field-ssot.yaml} 的 {@code gz_jp_event} 段。</p>
 *
 * <p><b>受控字段</b>（admin 可填）：name / coverImageId / description / startTime / endTime / sortNo / remark。<br>
 * <b>系统管理字段</b>（不接前端）：eventNo（系统生成）/ status（只能走 open / close 端点流转，
 * 不允许直接 PUT 改）/ version / 公共字段。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-JP-101)
 */
@Data
public class GzJpEventBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键（编辑时必传） */
    @NotNull(message = "场 ID 不能为空", groups = EditGroup.class)
    private Long id;

    /** 场名称 */
    @NotBlank(message = "场名称不能为空", groups = {AddGroup.class, EditGroup.class})
    @Size(max = 128, message = "场名称长度不能超过 128", groups = {AddGroup.class, EditGroup.class})
    private String name;

    /** 封面图 file id（gz_file_object.id；可空 —— 建场时可先不传图） */
    private Long coverImageId;

    /** 场简介 */
    @Size(max = 512, message = "场简介长度不能超过 512", groups = {AddGroup.class, EditGroup.class})
    private String description;

    /** 开场时间 */
    @NotNull(message = "开场时间不能为空", groups = {AddGroup.class, EditGroup.class})
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime startTime;

    /** 闭场时间（service 校验必须晚于开场时间） */
    @NotNull(message = "闭场时间不能为空", groups = {AddGroup.class, EditGroup.class})
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime endTime;

    /** 排序号，越小越前（不填按 0） */
    private Integer sortNo;

    /** 备注 */
    @Size(max = 500, message = "备注长度不能超过 500", groups = {AddGroup.class, EditGroup.class})
    private String remark;
}
