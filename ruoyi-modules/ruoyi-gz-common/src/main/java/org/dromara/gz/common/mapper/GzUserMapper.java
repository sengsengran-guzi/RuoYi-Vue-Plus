package org.dromara.gz.common.mapper;

import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.gz.common.domain.entity.GzUser;
import org.dromara.gz.common.domain.vo.GzUserVO;

/**
 * gz_user 数据层（GZ-SYS-003）。
 *
 * <p>多租户由 ruoyi {@code TenantLineInnerInterceptor} 自动 append {@code WHERE tenant_id = ?}；
 * 软删由 {@code @TableLogic} 自动过滤；分页由 {@code PaginationInnerInterceptor} 注入。
 * 因此本接口无需自定义 SQL，全部走 BaseMapperPlus 默认方法。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-SYS-003)
 */
public interface GzUserMapper extends BaseMapperPlus<GzUser, GzUserVO> {
}
