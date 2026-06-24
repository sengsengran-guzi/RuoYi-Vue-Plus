package org.dromara.gz.gacha.mapper;

import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.gz.gacha.domain.entity.GzGachaProduct;
import org.dromara.gz.gacha.domain.vo.GzGachaProductVo;

/**
 * gz_gacha_product 数据层（ADR-0013 / GZ-GACHA-112）。
 *
 * <p>多租户 / 软删 / 分页 / 乐观锁均由 ruoyi 拦截器自动处理（同 {@link GzGachaMachineMapper}）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-GACHA-112)
 */
public interface GzGachaProductMapper extends BaseMapperPlus<GzGachaProduct, GzGachaProductVo> {
}
