package org.dromara.gz.bean.domain.bo;

import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * mp 端付费预约提交参数（GZ-BEAN-017，V1.2 1h 区间连续多选）。
 *
 * <p>对应 {@code POST /app/gz/bean/booking/paid-submit}。一笔 = 1 座位类型 + 1 个连续 1h 区间
 * （{@code slotStart..slotEnd} 跨 N = (slotEnd − slotStart) 小时 个连续 1h 格，如 10:00..13:00 = 3 格）。
 * 字段名 / 数量不变（仍是单 {@code slotStart}/{@code slotEnd}），仅语义从「1 个命名时段」变为「N 连续 1h 格区间」。
 * {@code userId} / {@code openid} 由 sa-token 拿，不接受前端传入。</p>
 *
 * <p>权威：doc/15a §A.2 / ADR-0011（取代 ADR-0007/0008 的单笔单时段口径）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-BEAN-017)
 */
@Data
public class GzBeanPaidBookingSubmitBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 门店 ID（V1.0 仅成都拼豆店一家） */
    @NotNull
    private Long storeId;

    /** 座位类型 config id（gz_bean_seat_type_config.id；取代旧 seatType 字符串，ADR-0014 §5 配额计数 + 计价维度） */
    @NotNull
    private Long seatTypeConfigId;

    /** 预约日期（yyyy-MM-dd） */
    @NotNull
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate sessDate;

    /** 区间起（整点，含；HH:mm 或 HH:mm:ss） */
    @NotNull
    @DateTimeFormat(pattern = "HH:mm:ss")
    private LocalTime slotStart;

    /** 区间止（整点，不含；= 最后一个 1h 格的 end） */
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
