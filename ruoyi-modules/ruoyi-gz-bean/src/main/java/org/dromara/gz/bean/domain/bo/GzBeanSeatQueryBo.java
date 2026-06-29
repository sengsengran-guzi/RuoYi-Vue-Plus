package org.dromara.gz.bean.domain.bo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * gz_bean_seat 座位单元 admin 列表查询条件（ADR-0015）。
 *
 * @author kevin-coder (sensenran-guzi · GZ-BEAN-023)
 */
@Data
public class GzBeanSeatQueryBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 门店 ID */
    private Long storeId;

    /** 所属桌型 config ID（按桌型过滤座位单元） */
    private Long seatTypeConfigId;

    /** 座位号（模糊） */
    private String seatNo;

    /** 同桌聚合标识（精确） */
    private String tableNo;

    /** 0=停用 / 1=启用 */
    private Integer enabled;
}
