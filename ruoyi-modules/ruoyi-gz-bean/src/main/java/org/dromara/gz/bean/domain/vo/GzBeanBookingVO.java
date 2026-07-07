package org.dromara.gz.bean.domain.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import org.dromara.gz.bean.domain.entity.GzBeanBooking;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * gz_bean_booking 视图对象（GZ-BEAN-004）。
 *
 * <p>字段权威：doc/11 §3.4。admin / mp 共用。</p>
 *
 * <p><b>ID 类型 String 化</b>（dongjiaoshan 教训 #1）：id / userId / storeId / seatId 用
 * {@link ToStringSerializer} 序列化为 string，防 JS Number 精度丢失。</p>
 *
 * <p><b>不暴露</b>：verifyCode（核销码，敏感）/ dedupToken（内部去重 token）/ openid。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-BEAN-004)
 */
@Data
@AutoMapper(target = GzBeanBooking.class)
public class GzBeanBookingVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    private String bookingNo;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long userId;

    /** 组单 id（ADR-0018 §1）；单笔单为 null。同 group_id 的多条 = 一组（看板未排位列表按它聚合打「组·N人」标签 + 整组排位/核销） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long groupId;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long storeId;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long seatId;

    private String seatNoSnapshot;

    /** V1.2 座位类型（single/double/quad；前端 dict-tag gz_bean_seat_type 翻译） */
    private String seatType;

    /** V1.2 座位类型中文名快照 */
    private String seatTypeSnapshot;

    /** 桌型档 id（ADR-0016；看板②待分座区按 id 精确匹配候选空闲座，string 防 JS 精度丢失） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long seatTypeConfigId;

    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate sessDate;

    @JsonFormat(pattern = "HH:mm:ss")
    private LocalTime slotStart;

    @JsonFormat(pattern = "HH:mm:ss")
    private LocalTime slotEnd;

    /** 手机号（admin 看全 / mp 详情脱敏由前端处理） */
    private String mobileSnapshot;

    /** V1.2 付款前采集微信号快照（admin 看全） */
    private String wechatIdSnapshot;

    /** 包天单标记（GZ-BEAN-042）：1=包天套餐（全天占该座）/ 0=小时单。admin 列表/看板/待分座 tag + mp 详情用 */
    private Integer isDayPass;

    /** V1.2 本笔金额（分）= 座位类型单价 */
    private Long amountCent;

    /** V1.2 优惠券抵扣额（分） */
    private Long discountAmountCent;

    /** V1.2 FK → gz_user_coupon.id（未用券为 null） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long couponId;

    /** V1.2 支付单业务码（免费单为 null） */
    private String outTradeNo;

    /** 业务状态机 pending / used / cancelled / no_show（内部，与 payStatus 正交，ADR-0007） */
    private String status;

    /** V1.2 付费状态机 unpaid/paying/paid/pay_closed/refunded（内部，支付/配额/对账引擎用） */
    private String payStatus;

    /**
     * admin 展示用「单一综合状态」（派生码，dict-tag gz_bean_booking_status 翻译）。
     * 由 {@link org.dromara.gz.bean.service.impl.GzBeanBookingServiceImpl#deriveBizStatus} 从
     * (status, payStatus) 推导：paid/used/cancelled/refunded/no_show（真实订单）+ unpaid/closed（从没付成功）。
     */
    private String bizStatus;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime verifyTime;

    private String verifiedBy;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime cancelledTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime noShowTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;

    private String remark;

    // ============================================================
    //  GZ-BEAN-005 派生字段（非 DB 列，service 层填充；@AutoMapper 不映射）
    // ============================================================

    /**
     * 核销码 QR payload（mp 详情页渲染二维码用）。
     *
     * <p>格式 {@code "BK|{bookingNo}|{verifyCode}"}（{@link org.dromara.gz.bean.service.internal.QrCodeSigner}）。
     * <b>不持久化</b>：service 层按 (bookingNo + sessDate + seatId) 用 HMAC 即时重算，与 BEAN-004 submit 返回口径一致。
     * verifyCode 本身仍不暴露（doc/11 §3.6 敏感），仅以拼接进 payload 的形式给前端渲染。</p>
     */
    private String qrPayload;

    /** 门店名（mp 详情页顶部展示；service join gz_bean_store 填充） */
    private String storeName;

    /** 门店地址（mp 详情页顶部展示；service join gz_bean_store 填充） */
    private String storeAddress;

    // ============================================================
    //  GZ-BEAN-037 看板待分座区「同用户连续时段」高亮 + 提前核销（kevin-test §2，service 填充）
    // ============================================================

    /**
     * 该待分座单是否与同用户当前在店（used 未放座）单<b>时段相连</b>（kevin-test §2）：
     * true → 看板高亮 + 提示「同一用户连续时段」，店员可一键提前核销（默认沿用当前座位）。
     */
    private Boolean consecutiveWithActive;

    /** 建议沿用的座位 id（= 同用户相连 used 单的 seat_id；仅 consecutiveWithActive=true 时有值，弹窗默认预选） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long suggestedSeatId;

    /** 建议沿用的座位号（展示用，如 D5） */
    private String suggestedSeatNo;

    /** 同用户相连 used 单的占用止界（= 本待分座单时段的开始，展示「接续 14:00 后」用） */
    @JsonFormat(pattern = "HH:mm:ss")
    private LocalTime activeSlotEnd;

    // ============================================================
    //  GZ-BEAN-041 看板过期单批量结单 / 补核销（kevin-test §6，service 填充）
    // ============================================================

    /**
     * 时段已过的分钟数（{@code NOW() − TIMESTAMP(sess_date, slot_end)} 的分钟，&gt;0 即过期）。
     * 看板「过期待处理」区据此显「已过期 N 分钟/小时」红标。selectExpiredUnsettled 才填充。
     */
    private Long expiredMinutes;
}
