package org.dromara.gz.bean.domain.bo;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 看板过期单批量结单参数（GZ-BEAN-041 / kevin-test §6）。
 *
 * <p>对应 {@code POST /system/gz/bean/booking/batch-settle}。对一批过期单按 {@code action} 统一处理：
 * {@code completed}（补核销为已完成，pending|no_show → used）/ {@code no_show}（标爽约，pending → no_show）/
 * {@code released}（已超时 used 单标已结束 = 放座）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-BEAN-041)
 */
@Data
public class GzBeanBatchSettleBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 待处理预约 id 列表 */
    @NotEmpty
    private List<Long> bookingIds;

    /** 结单动作：completed / no_show / released */
    @NotNull
    private String action;
}
