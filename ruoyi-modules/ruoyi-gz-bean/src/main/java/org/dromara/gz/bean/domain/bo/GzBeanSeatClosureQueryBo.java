package org.dromara.gz.bean.domain.bo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * gz_bean_seat_closure admin 列表查询条件（GZ-BEAN-036，Req3）。
 *
 * @author kevin-coder (sensenran-guzi · GZ-BEAN-036)
 */
@Data
public class GzBeanSeatClosureQueryBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 门店 ID（精确） */
    private Long storeId;

    /** 座位 ID（精确） */
    private Long seatId;

    /** ISO 星期 1=Mon..7=Sun（精确） */
    private Integer weekday;

    /** 0=停用 / 1=生效（精确） */
    private Integer enabled;
}
