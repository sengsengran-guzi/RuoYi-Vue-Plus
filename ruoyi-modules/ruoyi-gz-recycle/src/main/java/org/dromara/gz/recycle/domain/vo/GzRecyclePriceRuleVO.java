package org.dromara.gz.recycle.domain.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

/**
 * 回收价目表 admin VO（GZ-RECYCLE-001，列表 + 详情共用）。
 *
 * <p>字段权威：doc/11 §12.1。ID 跨层契约 #1：Long 序列化为 string（前端 id 全 string）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-RECYCLE-001)
 */
@Data
public class GzRecyclePriceRuleVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键（序列化为 string） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** 回收品类（admin 走 sys_dict gz_recycle_category 回显 label） */
    private String category;

    /** 数量区间下界（含） */
    private Integer qtyMin;

    /** 数量区间上界（含；NULL = 无上界） */
    private Integer qtyMax;

    /** 该区间单价（分/件） */
    private Long unitPriceCent;

    /** 按数量匹配的核对/服务时长（分钟） */
    private Integer durationMinutes;

    /** 启用标志（0=停用 / 1=启用） */
    private Integer enabled;

    /** 同品类内排序 */
    private Integer sortNo;

    /** 创建时间（继承自 TenantEntity，类型 java.util.Date） */
    private Date createTime;

    /** 更新时间 */
    private Date updateTime;

    /** 备注 */
    private String remark;
}
