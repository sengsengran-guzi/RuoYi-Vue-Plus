package org.dromara.gz.common.pay.mapper;

import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.gz.common.pay.domain.entity.GzPayShippingOrder;
import org.dromara.gz.common.pay.domain.vo.GzPayShippingOrderVO;

/**
 * gz_pay_shipping_order 数据层（微信发货信息上报任务）。
 *
 * <p>读写均走 MyBatis-Plus 条件构造（service 内 LambdaQueryWrapper），无自定义 XML/SQL。
 * 第二型参 = {@link GzPayShippingOrderVO}：admin 列表 {@code selectVoPage} 自动映射为 VO。</p>
 *
 * @author kevin-coder (sensenran-guzi)
 */
public interface GzPayShippingOrderMapper extends BaseMapperPlus<GzPayShippingOrder, GzPayShippingOrderVO> {
}
