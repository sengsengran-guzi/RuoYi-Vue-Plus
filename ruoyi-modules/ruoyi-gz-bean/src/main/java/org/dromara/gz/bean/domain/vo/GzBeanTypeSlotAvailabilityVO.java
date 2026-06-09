package org.dromara.gz.bean.domain.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Builder;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalTime;

/**
 * mp 选座余量 VO（GZ-BEAN-014 AC 4，doc/10 §11.N3/N4 + doc/11 §3.6）。
 *
 * <p>某门店某日各 {@code (seat_type, 时段)} 的可约状态：单价 + 剩余配额。
 * 余量 = {@code gz_bean_seat_type_config.quantity − 活跃 booking 计数}；{@code N>0} 还剩 N，
 * {@code N≤0} 已满。mp 渲染「还剩 N 个单人座」/「已满」灰显。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-BEAN-014)
 */
@Data
@Builder
public class GzBeanTypeSlotAvailabilityVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 座位类型 value（single/double/quad） */
    private String seatType;

    /** 座位类型中文名（字典 gz_bean_seat_type 翻译） */
    private String seatTypeName;

    /** 该类型单价（分） */
    private Long priceCent;

    /** 时段开始 */
    @JsonFormat(pattern = "HH:mm:ss")
    private LocalTime slotStart;

    /** 时段结束 */
    @JsonFormat(pattern = "HH:mm:ss")
    private LocalTime slotEnd;

    /** 该类型该门店配额上限（admin 配置 quantity） */
    private Integer quantity;

    /** 当前活跃 booking 数（占名额） */
    private Long activeCount;

    /** 剩余可约数 = quantity − activeCount（下限 0） */
    private Integer remaining;

    /** 是否已满（remaining ≤ 0） */
    private Boolean full;
}
