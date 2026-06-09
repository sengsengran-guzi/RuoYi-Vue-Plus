package org.dromara.gz.bean.domain.bo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * mp 端付费预约提交参数（GZ-BEAN-014，V1.2 单笔单时段）。
 *
 * <p>对应 {@code POST /app/gz/bean/booking/paid-submit}。一笔 = 1 座位类型 + 1 时段（无 units 数组，
 * 无累加）。{@code userId} / {@code openid} 由 sa-token 拿，不接受前端传入。</p>
 *
 * <p>权威：doc/10 §11.N7 / doc/11 §3.5 / ADR-0007 / ADR-0008。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-BEAN-014)
 */
@Data
public class GzBeanPaidBookingSubmitBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 门店 ID（V1.0 仅成都拼豆店一家） */
    @NotNull
    private Long storeId;

    /** 座位类型（single/double/quad，字典 gz_bean_seat_type；取代旧 seatId） */
    @NotBlank
    private String seatType;

    /** 预约日期（yyyy-MM-dd） */
    @NotNull
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate sessDate;

    /** 时段开始时间（HH:mm 或 HH:mm:ss） */
    @NotNull
    @DateTimeFormat(pattern = "HH:mm:ss")
    private LocalTime slotStart;

    /** 时段结束时间 */
    @NotNull
    @DateTimeFormat(pattern = "HH:mm:ss")
    private LocalTime slotEnd;

    /**
     * 优惠券 ID（可选；券抵扣逻辑 D13 COUPON-002 实现，本卡仅留字段透传）。
     *
     * <p>未用券为 null。本卡不锁券、不算抵扣（discountAmountCent 恒 0），仅记录 couponId 占位。</p>
     */
    private Long couponId;

    /** mp 端生成的去重 token（UUID）— 防网络重试 / 快速连点重复提交，5s 窗口内幂等 */
    private String dedupClientToken;
}
