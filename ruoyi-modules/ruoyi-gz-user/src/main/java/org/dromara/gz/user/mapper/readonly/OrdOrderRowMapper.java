package org.dromara.gz.user.mapper.readonly;

import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.gz.user.domain.entity.readonly.OrdOrderRow;

/**
 * {@code gz_ord_order} 只读数据层（GZ-USER-101 订单聚合）。
 *
 * <p>仅供订单聚合 service UNION 读取预购订单；不写入（写入归 ruoyi-gz-ord）。
 * 多租户 {@code tenant_id} / 软删 {@code del_flag} 过滤由 ruoyi {@code TenantLineInnerInterceptor}
 * + {@code @TableLogic} 自动 append（service 不手写 WHERE，AC6）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-USER-101)
 */
public interface OrdOrderRowMapper extends BaseMapperPlus<OrdOrderRow, OrdOrderRow> {
}
