package org.dromara.gz.recycle.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDate;
import java.util.List;

/**
 * 回收小时格可用性（GZ-RECYCLE-012 / ADR-0022 改小时制后的新形态）。
 *
 * <p>mp 填单选时间用：某门店某日下，逐个 1 小时格带 {@code selectable}。
 * 占用真源 = 存在活跃预约的区间与该格 {@code [gi, gi+1h)} 重叠（每格容量 1）。</p>
 *
 * <p>{@link #spanHours} 一并回传，让前端不用自己算「这个点数档占几小时」 ——
 * 用它渲染「已选到店时间：09:00 - 13:00（占 4 小时）」的摘要与连续高亮。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-RECYCLE-012)
 */
@Data
public class RecycleSlotAvailabilityVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 查询日期 */
    private LocalDate date;

    /** 本次查询依据的占用小时数（未传 qtyBucketCode 时为 1） */
    private Integer spanHours;

    /** 该日全部 1 小时格（按起点升序；营业窗口切出，午休 gap 不生成） */
    private List<RecycleHourSlotVO> slots;
}
