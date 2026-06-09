package org.dromara.gz.coupon.domain.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Date;

/**
 * 用户券（发放记录）admin VO（GZ-COUPON-001）。
 *
 * <p>字段权威：doc/11 §11.2。ID 跨层契约 #1：Long 序列化为 string。userNickname / templateName
 * 由 service 回填（防 admin 端 N+1）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-COUPON-001)
 */
@Data
public class GzUserCouponVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键（序列化为 string） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** 业务码 UC-yyyyMMdd-6位序号 */
    private String couponNo;

    /** 模板 id（序列化为 string） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long templateId;

    /** 模板名（service 回填） */
    private String templateName;

    /** 用户 id（序列化为 string） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long userId;

    /** 用户昵称（service 回填，admin 列表展示） */
    private String userNickname;

    /** 用户手机号（service 回填，admin 列表展示） */
    private String userMobile;

    /** 券面额快照（分） */
    private Long amountSnapshotCent;

    /** 券态 unused/locked/used/expired */
    private String status;

    /** 领取时间 */
    private LocalDateTime gainedTime;

    /** 过期时间 */
    private LocalDateTime expireTime;

    /** 使用时间（非 used 态为 NULL） */
    private LocalDateTime usedTime;

    /** 核销关联的拼豆支付单 out_trade_no */
    private String relatedPayOutTradeNo;

    /** 创建时间 */
    private Date createTime;
}
