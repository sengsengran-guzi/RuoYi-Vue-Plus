package org.dromara.gz.bean.domain.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Builder;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalTime;

/**
 * admin 实时余量表格 VO（客户 0702 反馈 #4a）—— 明细数字版。
 *
 * <p>区别于 mp 的 {@link GzBeanTypeSlotAvailabilityVO}（只给 {@code full} 布尔，铁律不向 C 端暴露余量数字）：
 * 本 VO 是 <b>admin 后台专用</b>，明确回传各数字供店员在表格上「开放 8 / 已约 5 / 剩 3，关 1-3 个」直观操作。</p>
 *
 * <p>口径：{@code effectiveCap = opened − closedSeat − quotaClose}（下限 0），
 * {@code remaining = effectiveCap − booked}（下限 0，即 {@code remaining = opened − booked − closedSeat − quotaClose}）。
 * {@code opened} = 该桌型每格总配额（slotCapacity，按 book_mode 取）；
 * {@code closedSeat} = 老 {@code gz_bean_seat_closure} 按 seat_id + weekday 关闭折算的本桌型座位数；
 * {@code quotaClose} = 新 {@code gz_bean_slot_quota_close} 该具体日期该格的配额关闭数。</p>
 *
 * @author kevin-coder (sensenran-guzi · 客户 0702 反馈 #4a)
 */
@Data
@Builder
public class GzBeanSlotAvailabilityDetailVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 桌型档 id（string 防 JS 精度丢失，对齐跨层契约 #1；admin upsert 关闭数回传） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long seatTypeConfigId;

    /** 桌型 code（调试用） */
    private String seatType;

    /** 桌型显示名（表格行标题） */
    private String name;

    /** 订法 whole=整桌 / seat=按座 */
    private String bookMode;

    /** 该 1h 格起（整点，表格列标题） */
    @JsonFormat(pattern = "HH:mm")
    private LocalTime slotStart;

    /** 该 1h 格止（= start + 1h，整点） */
    @JsonFormat(pattern = "HH:mm")
    private LocalTime slotEnd;

    /** 开放总配额（slotCapacity，按 book_mode 取；不受关闭影响的名义容量） */
    private Long opened;

    /** 已约（该格覆盖的活跃单数，含 used） */
    private Long booked;

    /** 老 seat_id 关闭折算的本桌型座位数（gz_bean_seat_closure 按 weekday 周复发） */
    private Long closedSeat;

    /** 新配额关闭数（gz_bean_slot_quota_close 按具体日期，本表格 stepper 直接改写） */
    private Long quotaClose;

    /** 剩余 = max(0, opened − booked − closedSeat − quotaClose)；表格「剩余」列 + ≤0 灰显 */
    private Long remaining;
}
