package org.dromara.gz.bean.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.gz.bean.domain.entity.GzBeanSeatTypePrice;

import java.util.List;

/**
 * gz_bean_seat_type_price 数据层（GZ-BEAN-018，ADR-0014 §3）。
 *
 * <p>多租户 / 软删由 ruoyi 拦截器自动处理。下单 / 余量按 config 取该类型全部星期覆盖价，
 * service 层映射成 {@code weekday → priceCent}，命中则用、否则回退基础价。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-BEAN-018)
 */
public interface GzBeanSeatTypePriceMapper extends BaseMapperPlus<GzBeanSeatTypePrice, GzBeanSeatTypePrice> {

    /** 取某 config 的全部星期覆盖价（含未启用拦截器场景显式传 tenant，调用点用 selectList wrapper 亦可）。 */
    @Select("SELECT * FROM gz_bean_seat_type_price WHERE seat_type_config_id = #{configId} AND del_flag = '0'")
    List<GzBeanSeatTypePrice> selectByConfig(@Param("configId") Long configId);
}
