package org.dromara.gz.user.mapper;

import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.gz.user.domain.entity.GzUserAddress;
import org.dromara.gz.user.domain.vo.GzUserAddressVO;

/**
 * gz_user_address 数据层（GZ-USER-003）。
 *
 * <p>多租户 / 软删 / 分页由 ruoyi 拦截器自动处理，走 BaseMapperPlus 默认方法。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-USER-003)
 */
public interface GzUserAddressMapper extends BaseMapperPlus<GzUserAddress, GzUserAddressVO> {
}
