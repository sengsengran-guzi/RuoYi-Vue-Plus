package org.dromara.gz.common.cs.mapper;

import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.gz.common.cs.domain.entity.GzCustomerServiceConfig;

/**
 * gz_customer_service_config 数据层（GZ-SYS-004B）。
 *
 * <p>多租户由 ruoyi {@code TenantLineInnerInterceptor} 自动 append {@code WHERE tenant_id = ?}；
 * 每租户单行（UNIQUE(tenant_id)），查询走 service 内 selectOne(无 wrapper) 即取当前租户那行。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-SYS-004B)
 */
public interface GzCustomerServiceConfigMapper extends BaseMapperPlus<GzCustomerServiceConfig, GzCustomerServiceConfig> {
}
