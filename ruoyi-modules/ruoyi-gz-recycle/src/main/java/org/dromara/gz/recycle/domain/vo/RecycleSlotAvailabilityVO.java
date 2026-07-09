package org.dromara.gz.recycle.domain.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalTime;

/**
 * 回收到店时段可用性 VO（GZ-RECYCLE-007 放开）。
 *
 * <p>mp 填单选时段用：某门店某日下，逐个 enabled 时段带 {@code taken}（是否已被占）。
 * 占用真源 = 存在活跃预约 {@code time_slot_id=本档 OR spill_time_slot_id=本档}（每档容量 1，大单额外占下一档）。
 * 前端把 {@code taken=true} 的时段置灰禁选。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-RECYCLE-007)
 */
@Data
public class RecycleSlotAvailabilityVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 到店时段 id（序列化为 string，跨层契约 #1） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** 时段展示名（可空；空时 mp 用 "HH:mm-HH:mm" 自动拼） */
    private String label;

    /** 到店时段开始（HH:mm:ss） */
    @JsonFormat(pattern = "HH:mm:ss")
    private LocalTime startTime;

    /** 到店时段结束（HH:mm:ss） */
    @JsonFormat(pattern = "HH:mm:ss")
    private LocalTime endTime;

    /** 是否已被占（true=不可约，前端置灰） */
    private Boolean taken;
}
