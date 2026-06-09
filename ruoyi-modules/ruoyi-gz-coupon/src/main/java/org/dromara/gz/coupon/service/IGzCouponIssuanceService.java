package org.dromara.gz.coupon.service;

import org.dromara.gz.coupon.domain.bo.GzCouponIssueBo;
import org.dromara.gz.coupon.domain.vo.GzCouponIssueResultVO;

/**
 * 优惠券发放编排服务（GZ-COUPON-001 AC 4/5/6）。
 *
 * <p>按模板 {@code issue_strategy} 路由到 {@link org.dromara.gz.coupon.strategy.ICouponIssuanceStrategy}，
 * 事务内执行发放（乐观锁占配额 + 批量 INSERT unused 券）。V1.2 admin 主动发放仅放行 manual 策略模板。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-COUPON-001)
 */
public interface IGzCouponIssuanceService {

    /**
     * admin 批量发放（manual 策略，AC 5/6）。
     *
     * <p>校验：① 模板存在 + active；② 策略为 manual（admin 主动发放只放行 manual，event 走监听器）；
     * ③ 名单非空（userIds 优先，否则按 userKeyword 解析）。乐观锁防超发（配额耗尽拦截，AC 6）。</p>
     *
     * @param bo 发放请求（templateId + userIds / userKeyword）
     * @return 发放结果（实际发放数 + 剩余配额）
     */
    GzCouponIssueResultVO issue(GzCouponIssueBo bo);
}
