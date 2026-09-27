package org.dromara.gz.bean.domain.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Builder;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalTime;
import java.util.List;

/**
 * 店内计时看板「今日可售」抽屉行 VO（ADR-0024 §3，甲方 2026-09-26 红框位）。
 *
 * <p>一行 = 当日某门店一个<b>对小程序开放</b>（{@code mp_visible=1}）的桌型档。抽屉是「今天还剩多少可订」
 * 的信息面板 + 就地降配额，<b>不是新的可订性模型</b>：</p>
 * <ul>
 *   <li>{@link #slots} 是逐小时格明细（可订上限 / 已订 / 今日关闭 / 剩余），驱动抽屉里「不同时段还剩多少」表格；
 *       <b>逐时段</b>调整走 {@code POST /system/gz/bean/slotQuotaClose}（该格 upsert）；</li>
 *   <li><b>按天统一</b>调整走 {@code POST /system/gz/bean/slotQuotaClose/close-day}（该日全部小时格统一覆盖）；</li>
 *   <li>{@link #freeSeats} 只读：当天没被预订的座位（供店员判断线下来人能接几桌）。</li>
 * </ul>
 *
 * <p><b>关闭一律是「数量制」</b>（ADR-0018 §3 客户 7.05 定，2026-09-26 复核维持）：mp 顾客只选桌型档
 * 不选具体座位（ADR-0016），所以「留几个座」对顾客侧的<b>唯一</b>落地方式就是减该桌型该格的档位配额；
 * 不做座位级开关，也不按物理座位数折算（whole 模式的配额单位是「桌」，拿座数减配额量纲不符）。</p>
 *
 * <p>口径与 mp 余量 / admin 实时余量表同源（格集合 = {@code GzBeanHourSlotResolver}，
 * 逐格 {@code countActiveCoveringSlot} / {@code getQuotaClose}），不另立计数器。
 * 逐格 {@code remaining = max(0, capPerSlot − closeCount − booked)}，与 mp 有效配额逐字同式。</p>
 *
 * <p>跨层契约 #1：{@code seatTypeConfigId} / {@code freeSeats[].seatId} 用 {@code ToStringSerializer}
 * 转 string 防 JS long 精度丢失。</p>
 *
 * @author kevin-coder (sensenran-guzi · ADR-0024 §3)
 */
@Data
@Builder
public class GzBeanDaySellableVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 桌型档 id（string） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long seatTypeConfigId;

    /** 桌型显示名（config.name；空回退 seat_type code） */
    private String name;

    /** 订法 whole=整桌 / seat=按座 */
    private String bookMode;

    /** 该桌型每 1h 格的**名义容量**（seat → quantity×capacity / whole → quantity）；不含任何关闭 */
    private Long capPerSlot;

    /**
     * 长期关闭数（GZ-BEAN-057，来自桌型配置 {@code mp_long_close_count}）：该桌型长期不在小程序放出的档位数，
     * 单位同 {@link #capPerSlot}（whole=桌 / seat=座）。
     */
    private Long longCloseCount;

    /**
     * 「今天不填」时的默认可订量 = {@code capPerSlot − longCloseCount}（下限 0）。
     *
     * <p>逐格实际可订看 {@link SlotRow#getRemaining} —— 店员今天改过某格，就只有那格变。
     * 关闭数的合法上限是 {@link #capPerSlot}（总容量），不是这个值（GZ-BEAN-057：覆盖关系，不是相加）。</p>
     */
    private Long sellableCap;

    /** 当日小时格数（该日启用营业窗口切出的整点格数；午休那格不计） */
    private Integer slotCount;

    /**
     * 当日活跃单量（<b>去重计数</b>，不是逐格求和）。
     *
     * <p>口径 = 看板那条 SQL {@code status IN ('pending','used') AND pay_status IN ('paying','paid')}。
     * 跨多小时的单只算 1 单 —— 若逐格求和，一张 2 小时的单会被算成 2，店员看到「已订 5」而实际只有 3 单。</p>
     */
    private Long activeBookings;

    /**
     * 当日逐小时格明细（按格起整点升序；长度 = {@link #slotCount}）。
     *
     * <p>驱动抽屉里「不同时段还剩多少 / 今天暂时关闭几个」表格，也是逐格调整的 stepper 数据源。
     * 各格关闭数是否一致由前端从本列表推（不再下发「统一值 + 是否不一致」两个字段：
     * 单一真源是这个列表，多下发一份汇总就多一处可能分叉的口径）。</p>
     */
    private List<SlotRow> slots;

    /** 当天<b>没被预订</b>的座位（该桌型未软删座位 − 当天有活跃单的座位） */
    private List<FreeSeat> freeSeats;

    /**
     * 逐 1h 格明细行：可订上限取父行 {@link #capPerSlot}，剩余 = {@code max(0, cap − booked − closeCount)}。
     */
    @Data
    @Builder
    public static class SlotRow implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        /** 该 1h 格起整点（HH:mm） */
        @JsonFormat(pattern = "HH:mm")
        private LocalTime slotStart;

        /** 该 1h 格止（= start + 1h；当日最后一格 23:00 显示 00:00，与 mp 余量 VO 同约定） */
        @JsonFormat(pattern = "HH:mm")
        private LocalTime slotEnd;

        /** 覆盖该格的活跃单数（{@code countActiveCoveringSlot}，与 mp 余量同一条 SQL） */
        private Long booked;

        /**
         * 该格<b>今日生效</b>的关闭数 = 当日已记录值 ?? {@code longCloseCount}（覆盖关系，GZ-BEAN-057）。
         * 前端 stepper 预填这个值。
         */
        private Long closeCount;

        /**
         * 该格的 {@link #closeCount} 是否来自「今天没设」→ 沿用桌型配置的长期关闭。
         * {@code true} = 沿用默认（在桌型配置里改会跟着变）；{@code false} = 店员今天已经改过（只对今天有效）。
         */
        private Boolean closeInherited;

        /** 该格剩余可订 = {@code max(0, cap − closeCount − booked)} */
        private Long remaining;
    }

    /**
     * 「没被预订的座位」条目：座位是看板维度（{@code gz_bean_seat}），mp 不按座位售卖
     * （ADR-0024 §1），故本列表只用于展示 / 店员对照，无座位级开关。
     */
    @Data
    @Builder
    public static class FreeSeat implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        /** 座位单元 id（string） */
        @JsonSerialize(using = ToStringSerializer.class)
        private Long seatId;

        /** 座位编号（显示标签，如 S1 / D1 / Q1-1） */
        private String seatNo;

        /** 同桌聚合标识（seat 模式同桌多座聚成一组）；whole 可空 */
        private String tableNo;
    }
}
