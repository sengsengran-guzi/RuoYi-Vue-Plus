package org.dromara.gz.user.mapper;

import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.gz.user.domain.entity.GzUserAuditLog;
import org.dromara.gz.user.domain.vo.GzUserAuditLogVO;

/**
 * gz_user_audit_log 数据层（GZ-USER-002）。
 *
 * <p>多租户 / 软删 / 分页由 ruoyi 拦截器自动处理，本接口走 BaseMapperPlus 默认方法。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-USER-002)
 */
public interface GzUserAuditLogMapper extends BaseMapperPlus<GzUserAuditLog, GzUserAuditLogVO> {
}
