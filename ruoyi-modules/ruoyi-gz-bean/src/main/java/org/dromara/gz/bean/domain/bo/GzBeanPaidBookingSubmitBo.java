package org.dromara.gz.bean.domain.bo;

import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * mp 端付费预约提交参数（GZ-BEAN-024，影院选座 + 1h 区间连续多选）。
 *
 * <p>对应 {@code POST /app/gz/bean/booking/paid-submit}。一笔 = 1 <b>具体座位</b> + 1 个连续 1h 区间
 * （{@code slotStart..slotEnd} 跨 N = (slotEnd − slotStart) 小时 个连续 1h 格，如 10:00..13:00 = 3 格）。
 * {@code userId} / {@code openid} 由 sa-token 拿，不接受前端传入。</p>
 *
 * <p><b>影院选座（ADR-0015 §2/§3）</b>：入参从「座位类型 config」改为<b>具体座位 {@code seatId}</b>
 * —— 防超卖 = 该具体座位区间互斥；计价 / book_mode 经 {@code seatId → gz_bean_seat → seat_type_config} 取。</p>
 *
 * <p>权威：ADR-0015 §2/§3 / doc/11 §3.4「可用性接口 VO」/ §3.6（取代 ADR-0011/0014 的「按桌型 config 配额计数」入参）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-BEAN-024)
 */
@Data
public class GzBeanPaidBookingSubmitBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 门店 ID（V1.0 仅成都拼豆店一家） */
    @NotNull
    private Long storeId;

    /**
     * 具体座位单元 id（gz_bean_seat.id；影院选座，ADR-0015 §2 防超卖 + 经座位取桌型计价 / book_mode）。
     * 取代旧 seatTypeConfigId 入参（config 现由 seatId → seat → config 派生）。
     */
    @NotNull
    private Long seatId;

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
