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
 * <p>对应 {@code POST /app/gz/bean/booking/paid-submit}。一笔 = 1 <b>桌型档</b> + 1 个连续 1h 区间
 * （{@code slotStart..slotEnd} 跨 N = (slotEnd − slotStart) 小时 个连续 1h 格，如 10:00..13:00 = 3 格）。
 * {@code userId} / {@code openid} 由 sa-token 拿，不接受前端传入。</p>
 *
 * <p><b>下单选桌型（ADR-0016 §1/§2）</b>：入参为<b>桌型档 {@code seatTypeConfigId}</b>（单/双/四），
 * 用户不选具体座位、不显编号 —— 防超卖 = 该桌型档逐格配额计数；计价 / book_mode 经 config 取。
 * 具体物理座位由店员在<b>核销时</b>现场分配（ADR-0016 §3）。</p>
 *
 * <p>权威：ADR-0016 §1/§2 / doc/11 §3.4 / §3.6（取代 ADR-0015 的「具体座位 seatId 入参」）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-BEAN-034)
 */
@Data
public class GzBeanPaidBookingSubmitBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 门店 ID（V1.0 仅成都拼豆店一家） */
    @NotNull
    private Long storeId;

    /**
     * 桌型档 id（gz_bean_seat_type_config.id；ADR-0016 §1/§2 下单选桌型）。
     * 防超卖按该档逐格配额计数；计价 / book_mode / 容量从该 config 行取。
     * 具体物理座位（seat_id）核销时由店员现场分配（ADR-0016 §3），下单不绑座。
     */
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
