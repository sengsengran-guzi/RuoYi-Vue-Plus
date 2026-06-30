package org.dromara.gz.bean.domain.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Builder;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * 店内计时看板行 VO（GZ-BEAN-026，ADR-0015 §5 / doc/11 §3.12 / doc/10 §11 看板子流程）。
 *
 * <p>一行 = 当日某门店一个<b>启用且挂桌型</b>的座位单元（{@code gz_bean_seat.enabled=1 且
 * seat_type_config_id NOT NULL}）的实时状态。admin（plus-ui owner/店员，GZ-BEAN-028）+ mp 店员端
 * （GZ-BEAN-031）消费。座位无活跃单 → {@code status=idle}，{@code currentBooking* 字段全空}；
 * 有活跃单 → 回填该座当前（覆盖当前时刻或最近的活跃）单的 booking 信息 + 看板状态。</p>
 *
 * <p><b>看板状态机</b>（doc/11 §3.12 / doc/10 §11，由 service 按当前时刻 + booking 状态算）：</p>
 * <ul>
 *   <li>{@code idle} 空闲 —— 当前时刻无活跃 booking 覆盖该座</li>
 *   <li>{@code reserved} 已约未到 —— 有 pending+paid 活跃单覆盖当前/未来格，未核销（verify_time NULL）</li>
 *   <li>{@code in_use} 使用中 —— 已核销（status=used，verify_time 有值），当前时刻 &lt; 计划 slot_end；
 *       回填 {@code remainingMinutes}（到 slot_end 倒计时）</li>
 *   <li>{@code near_end} 临近结束 —— in_use 且 remainingMinutes ≤ 阈值（near_end_minutes，默认 15）→ 高亮</li>
 *   <li>{@code overtime} 已超时 —— 已核销且当前时刻 &gt; slot_end 且未放座（actual_end_time NULL）</li>
 * </ul>
 *
 * <p>跨层契约 #1：seatId / seatTypeConfigId / currentBookingId 用 {@code ToStringSerializer} 转 string
 * 防 JS long 精度丢失；对外业务码用 {@code bookingNo}（doc/11 跨层契约 #2）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-BEAN-026)
 */
@Data
@Builder
public class GzBeanBoardRowVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    // ---- 座位单元维度（恒有值） ----

    /** 座位单元 id（string） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long seatId;

    /** 座位/桌编号（显示标签，如 S1 / D1 / Q1-1） */
    private String seatNo;

    /** 同桌聚合标识（seat 模式同桌多座聚成一组）；whole 可空 */
    private String tableNo;

    /** 分区标签（影院图分区渲染） */
    private String zone;

    /** 所属桌型 config id（string） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long seatTypeConfigId;

    /** 桌型显示名（config.name；空回退 seat_type code） */
    private String typeName;

    /** 订法 whole=整桌 / seat=按座 */
    private String bookMode;

    /**
     * 看板状态：{@code idle} / {@code reserved} / {@code in_use} / {@code near_end} / {@code overtime}
     * （见类注释看板状态机）。
     */
    private String boardStatus;

    // ---- 当前活跃单维度（idle 时全空） ----

    /** 当前活跃单 id（string）；idle 时 null */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long currentBookingId;

    /** 当前活跃单业务码 BK...；idle 时 null */
    private String bookingNo;

    /** 当前活跃单计划区间起整点（如 14:00）；idle 时 null */
    private LocalTime slotStart;

    /** 当前活跃单计划区间止整点（如 17:00）；idle 时 null */
    private LocalTime slotEnd;

    /** 当前活跃单业务状态 pending / used；idle 时 null */
    private String status;

    /** 当前活跃单支付状态 paid；idle 时 null */
    private String payStatus;

    /** 是否免费单（1=免费）；idle 时 null */
    private Integer isFree;

    /** 预约人手机号快照（脱敏由前端处理；店员看全量）；idle 时 null */
    private String mobileSnapshot;

    /** 核销（入座/计时起点）时刻；未核销 / idle 时 null */
    private LocalDateTime verifyTime;

    /** 实际离场/放座时刻；未放座 / idle 时 null */
    private LocalDateTime actualEndTime;

    /**
     * 到计划 slot_end 的剩余分钟（仅 in_use / near_end 回填，可为负 = 已超时口径下不回填，见 overtime）；
     * 其余状态 null。near_end 判定即 {@code remainingMinutes ≤ 阈值}。
     */
    private Long remainingMinutes;

    /**
     * 该座当前单是否可延时（ADR-0016 §6 排满收尾信号）：紧邻后续 1h 格 {@code [slot_end, slot_end+1h)}
     * 未被本座别的活跃单占 → {@code true}（admin 提示「可问客人是否延时」）；已占 → {@code false}
     * （admin 提示「请客人收尾」）。仅 in_use / near_end / overtime 回填，其余 null。
     */
    private Boolean canExtend;
}
