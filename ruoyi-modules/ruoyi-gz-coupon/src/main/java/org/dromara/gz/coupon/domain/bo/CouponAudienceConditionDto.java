package org.dromara.gz.coupon.domain.bo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 券「条件筛选发放」单个 audience 条件（ADR-0010）。
 *
 * <p>承载 {@code issue_config_json.conditions[]} 的一项；按 {@code type} 路由到对应
 * {@link org.dromara.gz.coupon.strategy.ICouponAudienceCondition} 实现解析命中用户。
 * 不同 type 用到不同参数字段（无关字段留空）：</p>
 * <ul>
 *   <li>{@code register_time} — start / end（yyyy-MM-dd，含当天；至少一侧非空）</li>
 *   <li>{@code did_pindou} — completedOnly（true=仅已核销 used；false/空=有效预约 pending+used）</li>
 *   <li>{@code phone_bound} — 无参</li>
 * </ul>
 *
 * @author kevin-coder (sensenran-guzi · ADR-0010)
 */
@Data
public class CouponAudienceConditionDto implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 条件类型：register_time / did_pindou / phone_bound */
    private String type;

    /** register_time：注册时间下界（yyyy-MM-dd，含当天 00:00） */
    private String start;

    /** register_time：注册时间上界（yyyy-MM-dd，含当天 23:59:59） */
    private String end;

    /** did_pindou：true=仅已核销（used）；false/null=有效预约（pending+used） */
    private Boolean completedOnly;
}
