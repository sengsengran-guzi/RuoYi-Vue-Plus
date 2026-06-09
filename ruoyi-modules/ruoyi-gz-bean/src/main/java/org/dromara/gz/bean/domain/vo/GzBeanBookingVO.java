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

    @JsonSerialize(using = ToStringSerializer.class)
    private Long storeId;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long seatId;

    private String seatNoSnapshot;

    /** V1.2 座位类型（single/double/quad；前端 dict-tag gz_bean_seat_type 翻译） */
    private String seatType;

    /** V1.2 座位类型中文名快照 */
    private String seatTypeSnapshot;

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

    /** V1.2 本笔金额（分）= 座位类型单价 */
    private Long amountCent;

    /** V1.2 优惠券抵扣额（分） */
    private Long discountAmountCent;

    /** V1.2 FK → gz_user_coupon.id（未用券为 null） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long couponId;

    /** V1.2 支付单业务码（免费单为 null） */
    private String outTradeNo;

    private String status;

    /** V1.2 付费状态机 unpaid/paying/paid/pay_closed/refunded（前端 dict-tag gz_bean_pay_status 翻译） */
    private String payStatus;

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
}
