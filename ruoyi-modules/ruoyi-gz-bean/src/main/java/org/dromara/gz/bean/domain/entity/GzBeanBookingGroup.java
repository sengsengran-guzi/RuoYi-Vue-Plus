package org.dromara.gz.bean.domain.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * 拼豆组单支付聚合（ADR-0018 §1，gz_bean_booking_group）。
 *
 * <p>一家带 N 个孩子 = 一次下单 N 个单位。拆 {@code unitCount} 条子单（{@link GzBeanBooking#getGroupId()}=本组 id，
 * 每条 = 1 单位 = 1 座，复用现有单模型 + 逐格配额防超卖），支付一次挂本组：{@code out_trade_no / total_amount_cent /
 * pay_status} 落组级、驱动全组子单。子单各记 per-unit {@code amount_cent} 保 GMV 口径。</p>
 *
 * <p><b>组单不用券、不吃前 N 名免费促销</b>（甲方 7.05）：全价、{@code total_amount_cent} = Σ 子单区间价。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-BEAN-051)
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("gz_bean_booking_group")
public class GzBeanBookingGroup extends TenantEntity {

    /** 主键 */
    @TableId
    private Long id;

    /** 组业务码 BG-yyyyMMdd-6位序号 — UNIQUE(tenant_id, group_no) */
    private String groupNo;

    /** FK → gz_bean_store.id */
    private Long storeId;

    /** FK → gz_user.id（下单人） */
    private Long userId;

    /** 组桌型档（全组同桌型，FK → gz_bean_seat_type_config.id） */
    private Long seatTypeConfigId;

    /** 单位数 N（= 子单数 = 座位数） */
    private Integer unitCount;

    /** 预约日期（全组同日） */
    private LocalDate sessDate;

    /** 区间起（全组同区间） */
    private LocalTime slotStart;

    /** 区间止 */
    private LocalTime slotEnd;

    /** 组总额（分）= Σ 子单 amount_cent（组单全价，不用券/不吃促销） */
    private Long totalAmountCent;

    /** 组支付单业务码 PINDOU-yyyyMMdd-6位；未支付为 NULL — UNIQUE(tenant_id, out_trade_no) WHERE NOT NULL */
    private String outTradeNo;

    /** 组支付状态 unpaid / paying / paid / pay_closed / refunded（驱动全组子单，ADR-0007 正交 status） */
    private String payStatus;

    /** 下单人手机号快照 */
    private String mobileSnapshot;

    /** 下单人微信号快照 */
    private String wechatIdSnapshot;

    /** 去重 token（防误连点）— UNIQUE(tenant_id, store_id, dedup_token) */
    private String dedupToken;

    /** 乐观锁版本（mybatis-plus @Version） */
    @Version
    private Integer version;

    /** 备注 */
    private String remark;

    /** 软删（0=正常 / 1=删除） */
    private String delFlag;
}
