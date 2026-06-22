package org.dromara.gz.coupon.service;

import org.dromara.gz.coupon.domain.bo.CouponAudienceConditionDto;
import org.dromara.gz.coupon.domain.bo.GzCouponIssueBo;
import org.dromara.gz.coupon.domain.vo.GzCouponIssueResultVO;

import java.util.List;

/**
 * 优惠券发放编排服务（GZ-COUPON-001 AC 4/5/6 + ADR-0010 条件筛选）。
 *
 * <p>按模板 {@code issue_strategy} 路由到 {@link org.dromara.gz.coupon.strategy.ICouponIssuanceStrategy}，
 * 事务内执行发放（乐观锁占配额 + 批量 INSERT unused 券）。admin 主动发放放行 manual / filtered 策略。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-COUPON-001 / ADR-0010)
 */
public interface IGzCouponIssuanceService {

    /**
     * admin 批量发放（manual / filtered 策略，AC 5/6 + ADR-0010）。
     *
     * <p>校验：① 模板存在 + active；② 策略为 manual / filtered（event 走监听器）；
     * ③ manual 名单非空（userIds 优先，否则按 userKeyword 解析）；filtered 由策略按 issue_config_json
     * 解析 audience。乐观锁防超发（配额耗尽拦截，AC 6）。</p>
     *
     * @param bo 发放请求（templateId + manual 的 userIds / userKeyword）
     * @return 发放结果（实际发放数 + 剩余配额）
     */
    GzCouponIssueResultVO issue(GzCouponIssueBo bo);

    /**
     * 条件筛选「预览命中人数」（ADR-0010）：与 filtered 发放共用 audience 解析器（预览口径 = 实发口径）。
     *
     * @param conditions 条件列表（AND，≥1；非法即抛）
     * @return 命中用户数
     */
    long previewAudience(List<CouponAudienceConditionDto> conditions);
}
