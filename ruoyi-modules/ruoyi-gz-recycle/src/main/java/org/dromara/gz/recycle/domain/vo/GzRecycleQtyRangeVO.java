package org.dromara.gz.recycle.domain.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

/**
 * 回收数量桶 + 预计回收时长 VO（GZ-RECYCLE-004）。
 *
 * <p>admin 列表/详情 + mp 启用桶列表（mp 端消费 {@code code} / {@code label} / {@code durationMinutes}，
 * 契约 15a §C.2）。ID 跨层契约 #1：Long 序列化为 string。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-RECYCLE-004)
 */
@Data
public class GzRecycleQtyRangeVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键（序列化为 string） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** 桶机读码（mp 提交落 qtyBucketCode） */
    private String code;

    /** 桶展示文案（如 1-25 件） */
    private String label;

    /** 该桶预计回收时长（分钟） */
    private Integer durationMinutes;

    /** 启用标志（0=停用 / 1=启用）；mp 列表恒为启用桶，admin 列表全状态 */
    private Integer enabled;

    /** 展示排序（小在前） */
    private Integer sortNo;

    /** 创建时间（继承自 TenantEntity，类型 java.util.Date） */
    private Date createTime;

    /** 更新时间 */
    private Date updateTime;

    /** 备注 */
    private String remark;
}
