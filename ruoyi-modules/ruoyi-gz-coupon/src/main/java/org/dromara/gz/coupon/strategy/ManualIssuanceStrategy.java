package org.dromara.gz.coupon.strategy;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.gz.coupon.service.internal.CouponIssueWriter;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 手动发放策略（GZ-COUPON-001 §11.1.a，V1.2 全链路落地）。
 *
 * <p>doc/10 §12.N2：admin 选用户 / 筛名单（{@code ctx.targetUserIds}）→ 每用户生成一张 unused 券。
 * 配额乐观锁 + 批量 INSERT 由 {@link CouponIssueWriter} 共用实现（与 filtered 策略同款写入，ADR-0010）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-COUPON-001)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ManualIssuanceStrategy implements ICouponIssuanceStrategy {

    public static final String STRATEGY = "manual";

    private final CouponIssueWriter issueWriter;

    @Override
    public String supports() {
        return STRATEGY;
    }

    @Override
    public int issue(CouponIssuanceContext ctx) {
        List<Long> userIds = ctx.getTargetUserIds();
        if (userIds == null || userIds.isEmpty()) {
            throw new ServiceException("发放名单为空，无法发券");
        }
        return issueWriter.issueToUsers(ctx.getTemplate(), userIds);
    }
}
