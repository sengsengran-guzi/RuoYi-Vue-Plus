package org.dromara.gz.bean.domain.bo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * gz_bean_time_slot_template admin 列表查询条件（GZ-BEAN-002）。
 *
 * @author kevin-coder (sensenran-guzi · GZ-BEAN-002)
 */
@Data
public class GzBeanTimeSlotTemplateQueryBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 门店 ID */
    private Long storeId;

    /** 0=停用 / 1=启用 */
    private Integer enabled;
}
