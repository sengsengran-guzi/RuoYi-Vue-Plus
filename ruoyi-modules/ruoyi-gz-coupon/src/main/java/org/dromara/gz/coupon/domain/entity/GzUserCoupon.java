package org.dromara.gz.coupon.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.dromara.common.tenant.core.TenantEntity;

import java.io.Serial;
import java.time.LocalDateTime;

/**
 * gz_user_coupon — 用户已领券 entity（GZ-COUPON-001）。
 *
 * <p>字段口径权威：doc/11 §11.2 + §1 全局公共字段。承载券态机
 * （unused → locked → used / expired）。本卡只产 unused；流转 = GZ-COUPON-002（D13）。</p>
 *
 * <p><b>关键字段语义</b>：</p>
 * <ul>
 *   <li>{@code amountSnapshotCent} — 券面额快照，领券时从 template.amount_cent snapshot；模板改额不影响已发券</li>
 *   <li>{@code expireTime} — = gained_time + template.valid_days（发券时算）</li>
 *   <li>{@code relatedPayOutTradeNo} — 核销关联拼豆支付单 out_trade_no（追溯抵扣，不建独立 use 流水表）</li>
 * </ul>
 *
 * @author kevin-coder (sensenran-guzi · GZ-COUPON-001)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("gz_user_coupon")
public class GzUserCoupon extends TenantEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键（DB AUTO_INCREMENT） */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 业务码 UC-yyyyMMdd-6位序号 — UNIQUE(tenant_id, coupon_no, del_flag) */
    private String couponNo;

    /** FK → gz_coupon_template.id */
    private Long templateId;

    /** FK → gz_user.id（持券用户） */
    private Long userId;

    /** 券面额快照（分，领券时从 template.amount_cent snapshot） */
    private Long amountSnapshotCent;

    /** 券态 unused/locked/used/expired（字典 gz_coupon_status） */
    private String status;

    /** 领取时间 */
    private LocalDateTime gainedTime;

    /** 过期时间（= gained_time + template.valid_days） */
    private LocalDateTime expireTime;

    /** 使用时间（核销时写，非 used 态为 NULL） */
    private LocalDateTime usedTime;

    /** 核销关联的拼豆支付单 out_trade_no（追溯抵扣去向） */
    private String relatedPayOutTradeNo;

    /** 备注（公共字段，ruoyi 各 entity 显式定义） */
    private String remark;

    /** 软删标志（0=正常 / 2=删除，对齐 ruoyi） */
    @TableLogic
    private String delFlag;
}
