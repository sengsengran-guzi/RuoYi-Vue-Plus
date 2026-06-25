package org.dromara.gz.bean.domain.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Builder;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalTime;

/**
 * mp 选座余量 VO（GZ-BEAN-017，ADR-0011 / doc/15a §A.1）。
 *
 * <p>某门店某日各 {@code (seat_type, 1h 整点格)} 的可约状态：单价 + 是否已满。每个 (座位类型, 1h 格) 一档。</p>
 *
 * <p><b>铁律 — 不向 UI 暴露余量数字</b>（doc/15a §A.1）：后端内部仍按 {@code quantity − activeCount} 算出
 * {@code full}，但只把布尔 {@code full} 给 mp。旧字段 {@code quantity / activeCount / remaining} 已从 VO 移除，
 * mp 仅渲染「可约 / 已满」两态，不渲染任何余量数字。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-BEAN-017)
 */
@Data
@Builder
public class GzBeanTypeSlotAvailabilityVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 座位类型 config id（mp 提交时回传后端作 seatTypeConfigId，ADR-0014 §5） */
    private Long seatTypeConfigId;

    /** 座位类型 code（兼容/调试用；mp 不依赖） */
    private String seatType;

    /** 座位类型自定义显示名（取 config.name；mp 类型卡标题） */
    private String name;

    /** 订法 whole=整桌 / seat=按座（mp 据此显示「整桌 / 拼桌·按座」标签，ADR-0014 §6） */
    private String bookMode;

    /** 该类型单价（分/小时）；已按 sessDate 星期取生效价（覆盖价命中则用，否则基础价）；mp / 100 显示元/时 */
    private Long unitPriceCent;

    /** 1h 格起（整点） */
    @JsonFormat(pattern = "HH:mm:ss")
    private LocalTime slotStart;

    /** 1h 格止（= start + 1h，整点） */
    @JsonFormat(pattern = "HH:mm:ss")
    private LocalTime slotEnd;

    /** 该 1h 格该类型是否已满（后端 {@code remaining<=0}）；true → mp 灰显不可点 */
    private Boolean full;

    /**
     * 座位类型 config 是否启用（mp 契约字段：active=false 灰显/过滤）。
     * 余量接口仅查 enabled=1 的 config（service selectTypeSlotAvailability eq enabled=1），故恒 true；
     * 显式回传以满足 mp TypeSlotVO.active 契约，避免 undefined→falsy→整档被过滤。
     */
    private Boolean active;
}
