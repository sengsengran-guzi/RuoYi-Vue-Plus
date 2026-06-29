package org.dromara.gz.bean.mapper;

import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.gz.bean.domain.entity.GzBeanFreePromo;
import org.dromara.gz.bean.domain.vo.GzBeanFreePromoVO;

/**
 * gz_bean_free_promo 数据层（GZ-BEAN-025，ADR-0015 §4）。
 *
 * <p>多租户由 ruoyi {@code TenantLineInnerInterceptor} 自动 append {@code WHERE tenant_id=?}；
 * 软删由 {@code @TableLogic} 自动过滤。CRUD 全走 BaseMapperPlus 默认方法（无需自定义 SQL）。</p>
 *
 * <p><b>桶内已发免费数计数不在本 mapper</b>：下单事务内 / status 接口的桶计数走
 * {@link GzBeanBookingMapper#countBucketIssuedFree}（计数维度是 gz_bean_booking.is_free，非本表）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-BEAN-025)
 */
public interface GzBeanFreePromoMapper extends BaseMapperPlus<GzBeanFreePromo, GzBeanFreePromoVO> {
}
