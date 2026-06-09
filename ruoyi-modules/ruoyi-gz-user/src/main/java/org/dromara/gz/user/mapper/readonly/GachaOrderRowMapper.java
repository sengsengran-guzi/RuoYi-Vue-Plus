package org.dromara.gz.user.mapper.readonly;

import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.gz.user.domain.entity.readonly.GachaOrderRow;

/**
 * {@code gz_gacha_order} 只读数据层（GZ-USER-101 订单聚合）。
 *
 * <p>仅供订单聚合 service UNION 读取扭蛋衍生订单；不写入（写入归 ruoyi-gz-gacha）。
 * 多租户 / 软删过滤由 ruoyi 拦截器自动 append（service 不手写 WHERE，AC6）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-USER-101)
 */
public interface GachaOrderRowMapper extends BaseMapperPlus<GachaOrderRow, GachaOrderRow> {
}
