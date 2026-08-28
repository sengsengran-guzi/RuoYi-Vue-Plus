package org.dromara.gz.recycle.domain.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * 回收看板周视图 VO（ADR-0021 §3，当前周 × 该店 enabled 到店时段矩阵）。
 *
 * <p>{@code slots} = 列（该店 enabled 到店时段，与 {@code listEnabledByStore} 同源顺序）；{@code cells}
 * <b>只返被占格</b>（空闲格由前端用 slots × 7 天补齐）。「未完成预订」口径 = {@code ACTIVE_HOLD_STATUSES}
 * （与防超卖同源，doc/11 §12.7）。ID 跨层契约 #1：Long 序列化为 string。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-RECYCLE-010)
 */
@Data
public class GzRecycleWeekBoardVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long storeId;

    /** 周一（weekStart 入参归一到所在周的周一） */
    private LocalDate weekStart;

    /** 周日（weekStart + 6 天） */
    private LocalDate weekEnd;

    /**
     * 看板行 = 该店营业窗口切出的 1 小时格 ∪ 本周活跃单覆盖到的「孤儿格」（GZ-RECYCLE-012 / ADR-0022）。
     *
     * <p>并集是必须的：存量整档单（如 10:00-13:00 老单）或 admin 事后改窄窗口后，会有格「实际挡着下单
     * 但不在窗口里」。不做并集 → 店员看到空白却约不上，最难排查的一类投诉。</p>
     */
    private List<SlotVO> slots;

    /** 被占格（空闲格由前端补） */
    private List<CellVO> cells;

    /** 看板行头：一个 1 小时格（GZ-RECYCLE-012 起不再有 id —— 格由起点唯一标识）。 */
    @Data
    public static class SlotVO implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        /** 格起点（整点），前端用它做行 key */
        @JsonFormat(pattern = "HH:mm:ss")
        private LocalTime startTime;

        /** 格止点 = startTime + 1h */
        @JsonFormat(pattern = "HH:mm:ss")
        private LocalTime endTime;

        /**
         * 本行是否在当前营业窗口之外（孤儿格）：被存量单 / 改窄窗口留下的占用挡着，但已不可新约。
         * 前端应给可辨识标记，避免店员以为「这行是可用时间但一直约不上」。
         */
        private Boolean outOfWindow;
    }

    /**
     * 一个占用<b>区间块</b>：某日期 × {@code [slotStart, slotEnd)}（GZ-RECYCLE-012 / ADR-0022）。
     *
     * <p>一单发<b>一个</b>块（不是逐格发 N 个），前端按 {@code spanHours} 做 rowspan 合并渲染 ——
     * 逐格发会把一笔 4 小时单渲成 4 个格，等于把旧模型的 spill 噪音放大 4 倍。
     * {@code kind='spill'} 随 {@code spill_time_slot_id} 一起退休：多格占用现在由块自身的区间表达。</p>
     */
    @Data
    public static class CellVO implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        private LocalDate apptDate;

        /** 块起点（整点，= 本单区间起点向下取整） */
        @JsonFormat(pattern = "HH:mm:ss")
        private LocalTime slotStart;

        /** 块止点（整点，= 本单区间止点向上取整） */
        @JsonFormat(pattern = "HH:mm:ss")
        private LocalTime slotEnd;

        /** 本块跨几个 1 小时格（前端 rowspan） */
        private Integer spanHours;

        /** customer（顾客单）/ manual（手动占用） */
        private String kind;

        /** 源单 id */
        @JsonSerialize(using = ToStringSerializer.class)
        private Long appointmentId;

        private String appointmentNo;

        /** 记录状态（submitted/confirmed_onsite/paying/paid/payout_failed/manual_hold） */
        private String status;

        /** 记录来源 mp / manual */
        private String source;

        /** 联系手机号快照（source=mp 时有值） */
        private String mobileSnapshot;

        /** 点数档展示文案（source=mp 时有值） */
        private String qtyBucketLabel;

        /** 备注（顾客下单备注 / 店员手动占用备注） */
        private String remark;
    }
}
