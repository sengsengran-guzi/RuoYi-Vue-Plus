package org.dromara.gz.user.mapper.writable;

import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.gz.user.domain.entity.writable.GzOrdLogisticsRow;

/**
 * {@code gz_ord_order} 物流签收可写数据层（GZ-USER-104）。
 *
 * <p>仅供物流签收（用户确认 / 自动签收）做带乐观锁的条件 UPDATE；多租户 / 软删过滤走拦截器。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-USER-104)
 */
public interface GzOrdLogisticsMapper extends BaseMapperPlus<GzOrdLogisticsRow, GzOrdLogisticsRow> {
}
