package org.dromara.gz.jp.domain.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Date;

/**
 * 场 <b>admin 端</b> VO（GZ-JP-101，列表 + 详情共用，UI:admin.event）。
 *
 * <p>ID 跨层契约：所有 Long 主键 / 外键序列化为 string（JS number 精度丢失）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-JP-101)
 */
@Data
public class GzJpEventAdminVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键（序列化为 string） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** 场编号 EVT-yyyyMMdd-6位（系统生成，admin 只读） */
    private String eventNo;

    /** 场名称 */
    private String name;

    /** 封面图 file id（admin 走 file id + 缩略图组件回显，不在列表逐条预签名） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long coverImageId;

    /** 场简介 */
    private String description;

    /** 开场时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime startTime;

    /** 闭场时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime endTime;

    /**
     * <b>生效状态</b>（读时惰性判定后的 draft / open / closed）—— admin 列表展示与按钮可用性都用这个。
     *
     * <p>存库 open 但 end_time 已过的场，这里返回 closed（FLOW:F-JP-01.step4）。</p>
     */
    private String status;

    /** 存库原始状态（诊断用；与 {@link #status} 不一致即说明该场是「到点惰性结束」而非店员手动关场） */
    private String rawStatus;

    /** 排序号，越小越前 */
    private Integer sortNo;

    /** 乐观锁版本号 */
    private Integer version;

    /** 创建时间（继承自 TenantEntity，类型 java.util.Date） */
    private Date createTime;

    /** 更新时间 */
    private Date updateTime;

    /** 备注 */
    private String remark;
}
