package org.dromara.gz.coupon.strategy.condition;

import lombok.RequiredArgsConstructor;
import org.dromara.gz.common.service.IGzUserService;
import org.dromara.gz.coupon.domain.bo.CouponAudienceConditionDto;
import org.dromara.gz.coupon.strategy.ICouponAudienceCondition;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;

/**
 * 手机号已绑定条件（ADR-0010 phone_bound）。
 *
 * <p>命中 {@code gz_user.status='phone_bound'} 的有效用户（已走过 getPhoneNumber 绑定）。无参。</p>
 *
 * @author kevin-coder (sensenran-guzi · ADR-0010)
 */
@Component
@RequiredArgsConstructor
public class PhoneBoundCondition implements ICouponAudienceCondition {

    public static final String TYPE = "phone_bound";

    /** gz_user 已绑手机号状态值（对齐 doc/10 §1 状态机）。 */
    private static final String STATUS_PHONE_BOUND = "phone_bound";

    private final IGzUserService userService;

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public Set<Long> resolve(CouponAudienceConditionDto cond) {
        return new HashSet<>(userService.selectUserIdsByStatus(STATUS_PHONE_BOUND));
    }
}
