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

    /** 该店 enabled 到店时段列（与 listEnabledByStore 同源顺序） */
    private List<SlotVO> slots;

    /** 被占格（空闲格由前端补） */
    private List<CellVO> cells;

    /** 看板列头：一个到店时段格。 */
    @Data
    public static class SlotVO implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        @JsonSerialize(using = ToStringSerializer.class)
        private Long id;

        private String label;

        @JsonFormat(pattern = "HH:mm:ss")
        private LocalTime startTime;

        @JsonFormat(pattern = "HH:mm:ss")
        private LocalTime endTime;
    }

    /** 被占格：某日期 × 某到店时段的占用状况。 */
    @Data
    public static class CellVO implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        private LocalDate apptDate;

        @JsonSerialize(using = ToStringSerializer.class)
        private Long timeSlotId;

        /** customer（顾客单）/ manual（手动占用）/ spill（大单溢出占用，不可操作，需操作源单） */
        private String kind;

        /** 源单 id（spill 格指向大单本身；customer/manual 格 = 本记录 id） */
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
