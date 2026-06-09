package org.dromara.gz.coupon.domain.bo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 用户券（发放记录）列表查询对象（GZ-COUPON-001 admin 端）。
 *
 * @author kevin-coder (sensenran-guzi · GZ-COUPON-001)
 */
@Data
public class GzUserCouponQueryBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 模板 id 精确（看某模板发了哪些券） */
    private Long templateId;

    /** 用户 id 精确 */
    private Long userId;

    /** 券态精确（unused/locked/used/expired） */
    private String status;

    /** 券号模糊（UC-...） */
    private String couponNo;
}
