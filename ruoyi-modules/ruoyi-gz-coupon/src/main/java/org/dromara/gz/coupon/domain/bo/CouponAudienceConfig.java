package org.dromara.gz.coupon.domain.bo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 券「条件筛选发放」配置（ADR-0010）。
 *
 * <p>对应 {@code gz_coupon_template.issue_config_json} 的反序列化结构，也作为
 * 预览端点 {@code POST /preview-audience} 的请求体。条件之间 <b>AND</b> 组合（全部满足）。</p>
 *
 * @author kevin-coder (sensenran-guzi · ADR-0010)
 */
@Data
public class CouponAudienceConfig implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 筛选条件列表（AND 组合；条件筛选策略必须 ≥ 1 项） */
    private List<CouponAudienceConditionDto> conditions;
}
