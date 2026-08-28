package org.dromara.gz.bean.domain.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.fasterxml.jackson.annotation.JsonFormat;
import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import org.dromara.gz.bean.domain.entity.GzBeanSeat;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * gz_bean_seat 座位单元视图对象（ADR-0015）。
 *
 * <p>字段权威：doc/11 §3.3。admin 座位单元列表 / 详情消费；mp 影院选座（GZ-BEAN-029）
 * 另起 seat-map VO（按桌型/分区聚合 + full 布尔），本 VO 是 admin 维度。</p>
 *
 * <p>跨层契约 #1：id / storeId / seatTypeConfigId 用 {@code ToStringSerializer} 转 string
 * （防 JS long 精度丢失）。{@code typeName} / {@code bookMode} 由 Service join 桌型 config 回填，
 * admin 列表直接展示桌型显示名 + 订法。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-BEAN-023)
 */
@Data
@AutoMapper(target = GzBeanSeat.class)
public class GzBeanSeatVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键（string） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** 门店 id（string） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long storeId;

    /** 所属桌型 config id（string）；NULL = legacy 停用座 */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long seatTypeConfigId;

    /** 座位/桌编号（显示标签） */
    private String seatNo;

    /** 同桌聚合标识（seat 模式同桌多座聚成一组）；whole 可空 */
    private String tableNo;

    /** 分区标签（影院图分区渲染） */
    private String zone;

    /** 行标（影院图行列定位辅助） */
    private String rowLabel;

    /** 列序号（影院图行列定位辅助） */
    private Integer colIndex;

    /** 0=停用 / 1=启用 */
    private Integer enabled;

    /** 排序值 */
    private Integer sortNo;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 备注 */
    private String remark;

    /** 桌型显示名（Service 由 seatTypeConfigId join config.name 回填；legacy 座为空） */
    private String typeName;

    /** 订法 whole=整桌 / seat=按座（Service 由 config.book_mode 回填；legacy 座为空） */
    private String bookMode;

    /**
     * 本座所属桌型是否为**临时桌**（config.mp_visible=0，GZ-BEAN-054 / ADR-0023）。
     * Service 由 config 回填；供 admin 分座 / 改派 / 排位弹窗给临时桌选项打标并排在正常桌之后。
     */
    private Boolean temp;

    /**
     * 排位候选专用（selectPreAssignCandidates，ADR-0018 §2 客户 7.07）：本座在目标单时段是否可排位。
     * 区间重叠口径（与后端 preAssign 防超卖一致）：该座在目标 slot 无重叠活跃单 → true。
     * 其它查询（座位列表 / 核销分座 assignable-seats）不设此字段（null）。
     */
    private Boolean assignable;

    /**
     * 排位候选专用：本座被占用的止界时刻（COALESCE(actual_end_slot, slot_end) 的最大值）。
     * {@code assignable=false} 时非空，前端拼「占用至 HH:mm」告诉店员为何不可排。可排 / 非候选查询时为空。
     */
    @JsonFormat(pattern = "HH:mm")
    private LocalTime occupiedUntil;
}
