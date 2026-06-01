package org.dromara.gz.common.pay.mapper;

import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.gz.common.pay.domain.entity.GzPayChannel;
import org.dromara.gz.common.pay.domain.vo.GzPayChannelVO;

/**
 * gz_pay_channel 数据层（GZ-PAY-001）。多租户 / 软删由 ruoyi 拦截器自动处理。
 *
 * @author kevin-coder (sensenran-guzi · GZ-PAY-001)
 */
public interface GzPayChannelMapper extends BaseMapperPlus<GzPayChannel, GzPayChannelVO> {
}
