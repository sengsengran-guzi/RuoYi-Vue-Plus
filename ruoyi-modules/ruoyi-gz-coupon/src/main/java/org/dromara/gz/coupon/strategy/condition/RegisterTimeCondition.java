package org.dromara.gz.coupon.strategy.condition;

import cn.hutool.core.util.StrUtil;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.gz.common.service.IGzUserService;
import org.dromara.gz.coupon.domain.bo.CouponAudienceConditionDto;
import org.dromara.gz.coupon.strategy.ICouponAudienceCondition;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.Set;

/**
 * 注册时间区间条件（ADR-0010 register_time）。
 *
 * <p>命中 {@code gz_user.register_time} 落在 [start 00:00, end 23:59:59.999] 的有效用户。
 * 「新人=近 N 天」由 admin 把 start 配成相对今天的日期实现（前端算好绝对日期下发）。
 * start / end 至少一侧非空。</p>
 *
 * @author kevin-coder (sensenran-guzi · ADR-0010)
 */
@Component
@RequiredArgsConstructor
public class RegisterTimeCondition implements ICouponAudienceCondition {

    public static final String TYPE = "register_time";

    private final IGzUserService userService;

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public Set<Long> resolve(CouponAudienceConditionDto cond) {
        LocalDate startDate = parseDate(cond.getStart());
        LocalDate endDate = parseDate(cond.getEnd());
        if (startDate == null && endDate == null) {
            throw new ServiceException("注册时间条件需至少填写开始或结束日期");
        }
        LocalDateTime start = startDate == null ? null : startDate.atStartOfDay();
        LocalDateTime end = endDate == null ? null : endDate.atTime(LocalTime.MAX);
        if (start != null && end != null && start.isAfter(end)) {
            throw new ServiceException("注册时间条件开始日期不能晚于结束日期");
        }
        return new HashSet<>(userService.selectUserIdsByRegisterTimeBetween(start, end));
    }

    private LocalDate parseDate(String raw) {
        if (StrUtil.isBlank(raw)) {
            return null;
        }
        try {
            return LocalDate.parse(raw.trim());
        } catch (DateTimeParseException e) {
            throw new ServiceException("注册时间日期格式应为 yyyy-MM-dd：" + raw);
        }
    }
}
