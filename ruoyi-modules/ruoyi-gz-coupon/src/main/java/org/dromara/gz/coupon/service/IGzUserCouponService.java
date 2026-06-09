package org.dromara.gz.coupon.service;

import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.gz.coupon.domain.bo.GzUserCouponQueryBo;
import org.dromara.gz.coupon.domain.vo.GzUserCouponVO;

/**
 * 用户券（发放记录）查询服务（GZ-COUPON-001 AC 3，admin 端）。
 *
 * <p>本卡只读查发放记录；券态流转 / 抵扣 / 核销 = GZ-COUPON-002（D13）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-COUPON-001)
 */
public interface IGzUserCouponService {

    /** 分页查询发放记录（回填 templateName / userNickname / userMobile，防 N+1）。 */
    TableDataInfo<GzUserCouponVO> selectPage(GzUserCouponQueryBo query, PageQuery pageQuery);
}
