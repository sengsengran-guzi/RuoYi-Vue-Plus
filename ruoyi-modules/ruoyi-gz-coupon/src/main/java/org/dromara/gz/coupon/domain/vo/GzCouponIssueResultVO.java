package org.dromara.gz.coupon.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 批量发放结果 VO（GZ-COUPON-001 AC 5/6）。
 *
 * <p>admin 发放后回显：本次实际发放数 + 模板剩余可发数（total_quota 不限时为 null）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-COUPON-001)
 */
@Data
public class GzCouponIssueResultVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 本次实际发放张数 */
    private int issuedCount;

    /** 去重后请求发放的用户数 */
    private int requestedUserCount;

    /** 模板已发放总数（发放后） */
    private Integer templateIssuedCount;

    /** 模板总配额（NULL=不限） */
    private Integer totalQuota;

    /** 剩余可发（total_quota 不限时为 null） */
    private Integer remainingQuota;
}
