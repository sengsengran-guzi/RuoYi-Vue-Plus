package org.dromara.gz.coupon.domain.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

/**
 * 券模板 admin VO（GZ-COUPON-001，列表 + 详情共用）。
 *
 * <p>字段权威：doc/11 §11.1。ID 跨层契约 #1：Long 序列化为 string（前端 id 全 string）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-COUPON-001)
 */
@Data
public class GzCouponTemplateVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键（序列化为 string） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** 业务码 CPN-yyyyMMdd-6位序号（系统生成，admin 只读） */
    private String templateNo;

    /** 券名 */
    private String name;

    /** 折扣类型 cash/full_reduce/percent（admin 走 sys_dict gz_coupon_discount_type 回显 label） */
    private String discountType;

    /** 代金券固定抵扣额（分） */
    private Long amountCent;

    /** 适用业务 pindou */
    private String applicableBusiness;

    /** 领券后有效天数 */
    private Integer validDays;

    /** 模板总发放配额（NULL=不限） */
    private Integer totalQuota;

    /** 已发放数 */
    private Integer issuedCount;

    /** 发放策略 manual/filtered/event */
    private String issueStrategy;

    /** 策略参数 JSON */
    private String issueConfigJson;

    /** 模板态 active/paused/archived */
    private String status;

    /** 乐观锁版本（admin 只读，发放并发调试用） */
    private Integer version;

    /** 创建时间（继承自 TenantEntity，类型 java.util.Date） */
    private Date createTime;

    /** 更新时间 */
    private Date updateTime;

    /** 备注 */
    private String remark;
}
