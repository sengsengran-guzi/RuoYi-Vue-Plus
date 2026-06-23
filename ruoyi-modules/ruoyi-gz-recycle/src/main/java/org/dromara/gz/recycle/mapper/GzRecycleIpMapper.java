package org.dromara.gz.recycle.mapper;

import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.gz.recycle.domain.entity.GzRecycleIp;

/**
 * gz_recycle_ip 数据层（GZ-RECYCLE-004）。
 *
 * <p>多租户由 ruoyi {@code TenantLineInnerInterceptor} 自动 append {@code WHERE tenant_id = ?}；
 * 软删由 {@code @TableLogic} 自动过滤；分页由 {@code PaginationInnerInterceptor} 注入。
 * 查询条件全走 {@code LambdaQueryWrapper} 在 service 组装（不写 XML），便于单测 mock。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-RECYCLE-004)
 */
public interface GzRecycleIpMapper extends BaseMapperPlus<GzRecycleIp, GzRecycleIp> {
}
