package org.dromara.gz.recycle.domain.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Date;

/**
 * 回收到店时段 VO（GZ-RECYCLE-006）。
 *
 * <p>admin 列表/详情 + mp 启用时段列表（mp 端消费 {@code id} / {@code label} / {@code startTime} /
 * {@code endTime}，单选后提交 timeSlotId）。ID 跨层契约 #1：Long 序列化为 string；
 * 时间序列化 "HH:mm:ss"。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-RECYCLE-006)
 */
@Data
public class GzRecycleTimeSlotVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键（序列化为 string） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** 所属门店 id（序列化为 string） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long storeId;

    /** 门店名（join gz_bean_store，admin 列表回显） */
    private String storeName;

    /** 时段展示名（可空；空时 mp 用 "HH:mm-HH:mm" 自动拼） */
    private String label;

    /** 到店时段开始（HH:mm:ss） */
    @JsonFormat(pattern = "HH:mm:ss")
    private LocalTime startTime;

    /** 到店时段结束（HH:mm:ss） */
    @JsonFormat(pattern = "HH:mm:ss")
    private LocalTime endTime;

    /** 生效星期（ISO 1=周一..7=周日，逗号分隔）—— GZ-RECYCLE-015 */
    private String weekdays;

    /** 生效起（NULL = 立即生效） */
    private LocalDate effectiveDate;

    /** 生效止（NULL = 长期有效） */
    private LocalDate expireDate;

    /** 启用标志（0=停用 / 1=启用）；mp 列表恒为启用，admin 列表全状态 */
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
