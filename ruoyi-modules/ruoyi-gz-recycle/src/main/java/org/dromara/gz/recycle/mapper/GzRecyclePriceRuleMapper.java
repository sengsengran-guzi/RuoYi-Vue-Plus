package org.dromara.gz.recycle.mapper;

import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.gz.recycle.domain.entity.GzRecyclePriceRule;

/**
 * gz_recycle_price_rule 数据层（GZ-RECYCLE-001）。
 *
 * <p>多租户由 ruoyi {@code TenantLineInnerInterceptor} 自动 append {@code WHERE tenant_id = ?}；
 * 软删由 {@code @TableLogic} 自动过滤；分页由 {@code PaginationInnerInterceptor} 注入。</p>
 *
 * <p>区间不重叠校验 + 估价命中均走 {@link com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper}
 * 在 service 层组装（不写 XML / 自定义 SQL），便于单测 mock。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-RECYCLE-001)
 */
public interface GzRecyclePriceRuleMapper extends BaseMapperPlus<GzRecyclePriceRule, GzRecyclePriceRule> {
}
