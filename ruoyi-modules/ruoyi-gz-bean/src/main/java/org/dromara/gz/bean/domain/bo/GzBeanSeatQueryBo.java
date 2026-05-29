package org.dromara.gz.bean.domain.bo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * gz_bean_seat admin 列表查询条件（GZ-BEAN-002）。
 *
 * @author kevin-coder (sensenran-guzi · GZ-BEAN-002)
 */
@Data
public class GzBeanSeatQueryBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 门店 ID */
    private Long storeId;

    /** 座位号（模糊） */
    private String seatNo;

    /** 0=停用 / 1=启用 */
    private Integer enabled;
}
