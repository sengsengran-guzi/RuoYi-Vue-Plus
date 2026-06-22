package org.dromara.gz.coupon.strategy;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.gz.coupon.domain.entity.GzCouponTemplate;
import org.dromara.gz.coupon.service.internal.CouponIssueWriter;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Set;

/**
 * 条件筛选发放策略（ADR-0010 filtered）。
 *
 * <p>admin 自助配置 audience 条件（注册时间区间 / 做过拼豆 / 手机号已绑定 …，AND 组合），
 * 发放时由 {@link CouponAudienceResolver} 解析 {@code issue_config_json} 圈定命中用户，再复用
 * {@link CouponIssueWriter} 走配额乐观锁 + 批量 INSERT。命中 0 人则抛错回滚（不空发）。</p>
 *
 * <p>「预览命中 N 人」与本策略共用 resolver，预览口径 = 实发口径。新增条件维度只补
 * {@link ICouponAudienceCondition} 实现，本策略不改。</p>
 *
 * <p>事务由 {@code GzCouponIssuanceServiceImpl#issue} 统一开启。</p>
 *
 * @author kevin-coder (sensenran-guzi · ADR-0010)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FilteredIssuanceStrategy implements ICouponIssuanceStrategy {

    public static final String STRATEGY = "filtered";

    private final CouponAudienceResolver audienceResolver;
    private final CouponIssueWriter issueWriter;

    @Override
    public String supports() {
        return STRATEGY;
    }

    @Override
    public int issue(CouponIssuanceContext ctx) {
        GzCouponTemplate template = ctx.getTemplate();
        Set<Long> audience = audienceResolver.resolveByConfigJson(template.getIssueConfigJson());
        if (audience.isEmpty()) {
            throw new ServiceException("条件筛选命中 0 个用户，无法发放（请调整条件或先预览）");
        }
        return issueWriter.issueToUsers(template, new ArrayList<>(audience));
    }
}
