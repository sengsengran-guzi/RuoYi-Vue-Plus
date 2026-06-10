package org.dromara.gz.coupon.domain.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 用户券 mp 端 VO（GZ-COUPON-003）。
 *
 * <p>字段权威：doc/11 §11.2。mp 端「我的优惠券」列表 + 选券模块用。与 admin
 * {@link GzUserCouponVO} 区分：mp 是用户查自己的券，<b>不带 userNickname/userMobile</b>（隐私 +
 * 无 N+1 需求），改带 {@code templateName}（券名）+ {@code applicableBusiness}（适用范围，从
 * gz_coupon_template join 回填，mp 展示「仅拼豆可用」）。</p>
 *
 * <p>ID 跨层契约 #1：Long 序列化为 string（mp Java Long ↔ JS number 精度坑）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-COUPON-003)
 */
@Data
public class GzUserCouponMpVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键（序列化为 string） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** 业务码 UC-yyyyMMdd-6位序号（mp 我的券展示） */
    private String couponNo;

    /** 模板 id（序列化为 string） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long templateId;

    /** 券名（从 gz_coupon_template.name join 回填） */
    private String templateName;

    /** 适用业务 pindou（从 gz_coupon_template.applicable_business join 回填，mp 展示适用范围） */
    private String applicableBusiness;

    /** 券面额快照（分）；mp 端 / 100 显示元 */
    private Long amountSnapshotCent;

    /** 券态 unused/locked/used/expired（字典 gz_coupon_status） */
    private String status;

    /** 领取时间 */
    private LocalDateTime gainedTime;

    /** 过期时间（mp 展示「X 月 X 日过期」） */
    private LocalDateTime expireTime;

    /** 使用时间（非 used 态为 NULL） */
    private LocalDateTime usedTime;
}
