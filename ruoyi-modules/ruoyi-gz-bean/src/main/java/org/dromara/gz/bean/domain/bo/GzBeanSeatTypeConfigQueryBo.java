package org.dromara.gz.bean.domain.bo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * gz_bean_seat_type_config admin 列表查询条件（GZ-BEAN-013）。
 *
 * <p>主用按 storeId 筛（一个门店配几行类型配额）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-BEAN-013)
 */
@Data
public class GzBeanSeatTypeConfigQueryBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 门店 ID */
    private Long storeId;

    /** 座位类型（精确，字典 gz_bean_seat_type 的 value） */
    private String seatType;

    /** 0=停用 / 1=启用 */
    private Integer enabled;

    /** 是否对小程序开放：1=正常桌型 / 0=仅后台临时桌（GZ-BEAN-054）；不传则不过滤 */
    private Integer mpVisible;
}
