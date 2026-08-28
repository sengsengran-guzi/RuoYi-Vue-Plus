package org.dromara.gz.recycle.domain.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalTime;

/**
 * 一个 1 小时格的可选性（GZ-RECYCLE-012 / ADR-0022，取代 {@code RecycleSlotAvailabilityVO} 的档模型）。
 *
 * <p>格由起点唯一标识（不再有 id）。前端<b>只看 {@link #selectable}</b> 决定能不能点 ——
 * 「从这一格起放不放得下 N 小时」的规则综合了 ① 营业窗口连续性 ② 午休 gap 不可桥接
 * ③ 今日已过时刻 ④ 逐格占用 ⑤ 跨窗口不可连占 五条后端知识，前端复刻必然漂移。</p>
 *
 * <p>{@link #taken} 与 {@link #selectable} 是两件事：一格自己空着（{@code taken=false}）但从它起
 * 放不下所需时长时，仍然 {@code selectable=false}。前端据此给不同文案（「已约满」vs「放不下 4 小时」），
 * 否则用户会困惑「为什么 12 点不能点」。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-RECYCLE-012)
 */
@Data
public class RecycleHourSlotVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 格起点（整点）；前端用它做 key，提交时原样回传为 slotStart */
    @JsonFormat(pattern = "HH:mm:ss")
    private LocalTime startTime;

    /** 格止点 = startTime + 1h */
    @JsonFormat(pattern = "HH:mm:ss")
    private LocalTime endTime;

    /** 展示文案 "HH:mm"（前端直接渲染，免去两端各自格式化） */
    private String label;

    /** 本格自身已被占（看板 / 展示口径） */
    private Boolean taken;

    /** 今天且该时刻已过（{@code date == today && startTime <= now}） */
    private Boolean past;

    /** 以本格为起点能否放下整段 N 小时 —— <b>前端唯一该看的字段</b> */
    private Boolean selectable;
}
