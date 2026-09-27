package org.dromara.gz.bean.domain.vo;

import lombok.Builder;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 看板「今日可售」批量配额关闭回执 VO（ADR-0024 §3）。
 *
 * <p>返回本次实际写到的格数与统一关闭值，前端据此刷新「今天少卖 N 个」stepper 与余量显示
 * （{@code slotCount=0} = 该日没有营业格，一格都没写）。</p>
 *
 * @author kevin-coder (sensenran-guzi · ADR-0024 §3)
 */
@Data
@Builder
public class GzBeanSlotQuotaCloseDayVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 本次写入的小时格数（该日启用营业窗口切出的整点格数） */
    private Integer slotCount;

    /** 每格统一写入的关闭数（= 请求的 closeCount） */
    private Integer closeCount;

    /** 该桌型每格可售上限（slotCapacity；closeCount 的合法上限，回传供前端校差） */
    private Long cap;
}
