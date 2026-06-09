package org.dromara.gz.coupon.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.gz.coupon.domain.entity.GzUserCoupon;

/**
 * gz_user_coupon 数据层（GZ-COUPON-001）。
 *
 * <p>多租户 / 软删 / 分页由 ruoyi 拦截器统一处理。本卡只写入 unused 券 + admin 查发放记录。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-COUPON-001)
 */
public interface GzUserCouponMapper extends BaseMapperPlus<GzUserCoupon, GzUserCoupon> {

    /**
     * 查当日某 coupon_no 前缀的最大序号（GZ-COUPON-001 业务码生成，与 booking_no / article_no 同款）。
     *
     * <p>{@code SELECT MAX(coupon_no)}：当日已用最大单号；service 截后 6 位 +1。
     * 含已软删（del_flag 不限）—— 业务码全局序号空间不复用软删号，避免重号。多租户由拦截器 append。</p>
     *
     * @param prefixDate 形如 "UC-20260620-"
     * @return 命中最大 coupon_no（无则 null）
     */
    @Select("SELECT MAX(coupon_no) FROM gz_user_coupon WHERE coupon_no LIKE CONCAT(#{prefixDate}, '%')")
    String selectMaxCouponNo(@Param("prefixDate") String prefixDate);
}
