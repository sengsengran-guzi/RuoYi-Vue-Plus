package org.dromara.gz.recycle.domain.bo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 回收到店时段列表查询对象（GZ-RECYCLE-006 admin 端）。
 *
 * @author kevin-coder (sensenran-guzi · GZ-RECYCLE-006)
 */
@Data
public class GzRecycleTimeSlotQueryBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 门店精确（admin 按门店筛时段，必选门店看其时段列表） */
    private Long storeId;

    /** 启用标志精确（0=停用 / 1=启用） */
    private Integer enabled;
}
