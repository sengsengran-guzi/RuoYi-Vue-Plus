package org.dromara.gz.coupon.domain.bo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 券模板列表查询对象（GZ-COUPON-001 admin 端）。
 *
 * @author kevin-coder (sensenran-guzi · GZ-COUPON-001)
 */
@Data
public class GzCouponTemplateQueryBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 券名模糊 */
    private String name;

    /** 模板态精确（active/paused/archived） */
    private String status;

    /** 发放策略精确（manual/register_window/event） */
    private String issueStrategy;

    /** 折扣类型精确（cash） */
    private String discountType;
}
