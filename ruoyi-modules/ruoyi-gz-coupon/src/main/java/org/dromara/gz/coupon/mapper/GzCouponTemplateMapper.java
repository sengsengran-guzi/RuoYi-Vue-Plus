package org.dromara.gz.coupon.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.gz.coupon.domain.entity.GzCouponTemplate;

/**
 * gz_coupon_template 数据层（GZ-COUPON-001）。
 *
 * <p>多租户由 ruoyi {@code TenantLineInnerInterceptor} 自动 append {@code WHERE tenant_id = ?}；
 * 软删由 {@code @TableLogic} 自动过滤；分页由 {@code PaginationInnerInterceptor} 注入。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-COUPON-001)
 */
public interface GzCouponTemplateMapper extends BaseMapperPlus<GzCouponTemplate, GzCouponTemplate> {

    /**
     * 原子加配额计数（GZ-COUPON-001 AC 6 防超发，doc/11 §11.4 F11.4）。
     *
     * <p>单条 {@code UPDATE ... SET issued_count = issued_count + #{n}, version = version + 1
     * WHERE id = #{id} AND version = #{version} AND (total_quota IS NULL OR issued_count + #{n} <= total_quota)}：
     * 把「读 version + 校验配额 + 写 issued_count」并到一条 DB 行锁内，杜绝两并发线程同时通过校验后双写超发。
     * 返回 0 = version 已被并发改 / 配额已耗尽 → service 拦截发放（事务回滚）。</p>
     *
     * <p>多租户拦截器对 {@code @Update} 自定义 SQL 同样生效（自动 append tenant 条件）。</p>
     *
     * @param id      模板主键
     * @param version 读取时的乐观锁版本（CAS 比对）
     * @param n       本批发放数量
     * @return 影响行数（1=成功扣减；0=版本失配或配额不足）
     */
    @Update("UPDATE gz_coupon_template "
        + "SET issued_count = issued_count + #{n}, version = version + 1, update_time = now() "
        + "WHERE id = #{id} AND version = #{version} AND del_flag = '0' AND status = 'active' "
        + "AND (total_quota IS NULL OR issued_count + #{n} <= total_quota)")
    int increaseIssuedCount(@Param("id") Long id, @Param("version") Integer version, @Param("n") int n);
}
