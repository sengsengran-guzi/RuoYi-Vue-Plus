package org.dromara.gz.coupon.strategy.condition;

import lombok.RequiredArgsConstructor;
import org.dromara.gz.common.service.IGzUserService;
import org.dromara.gz.coupon.domain.bo.CouponAudienceConditionDto;
import org.dromara.gz.coupon.strategy.ICouponAudienceCondition;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;

/**
 * 做过拼豆条件（ADR-0010 did_pindou）。
 *
 * <p>命中存在拼豆预约的有效用户：{@code completedOnly=true} 仅已核销（status='used'），
 * 否则有效预约（status in pending,used，排除已取消/爽约）。跨域查询走
 * {@link IGzUserService#selectUserIdsWithBeanBooking}（gz-common 统一承载，gz-coupon 不直连 bean 表）。</p>
 *
 * @author kevin-coder (sensenran-guzi · ADR-0010)
 */
@Component
@RequiredArgsConstructor
public class DidPindouCondition implements ICouponAudienceCondition {

    public static final String TYPE = "did_pindou";

    private final IGzUserService userService;

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public Set<Long> resolve(CouponAudienceConditionDto cond) {
        boolean completedOnly = Boolean.TRUE.equals(cond.getCompletedOnly());
        return new HashSet<>(userService.selectUserIdsWithBeanBooking(completedOnly));
    }
}
