package org.dromara.gz.ord.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.gz.ord.domain.entity.GzOrdProduct;

/**
 * gz_ord_product 数据层（GZ-ORD-101）。
 *
 * <p>多租户由 ruoyi {@code TenantLineInnerInterceptor} 自动 append {@code WHERE tenant_id = ?}；
 * 软删由 {@code @TableLogic} 自动过滤；分页由 {@code PaginationInnerInterceptor} 注入；
 * 乐观锁由 {@code OptimisticLockerInnerInterceptor} 对 updateById 生效。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-ORD-101)
 */
public interface GzOrdProductMapper extends BaseMapperPlus<GzOrdProduct, GzOrdProduct> {

    /**
     * 截止下架批量 UPDATE（GZ-ORD-101 AC 5，截止 cron 唯一写 auto_off 的路径，决策 D5）。
     *
     * <p>{@code on_shelf AND deadline_time < now()} → {@code auto_off}。单条批量 UPDATE 天然原子；
     * WHERE 二次校验 status='on_shelf' 保证幂等可重跑（已 auto_off / off_shelf 不重复处理）。
     * 多租户由拦截器对注解 SQL 自动 append tenant 条件。</p>
     *
     * @return 实际自动下架行数（0 = 当前无到期 on_shelf 商品）
     */
    @Update("UPDATE gz_ord_product "
        + "SET status = 'auto_off', update_time = now(3) "
        + "WHERE status = 'on_shelf' AND deadline_time < now(3) AND del_flag = '0'")
    int autoOffExpiredProducts();

    /**
     * 销量累加（ORD-104 支付成功时调用；本 ticket 预置原子 UPDATE，不读改写防并发丢失）。
     *
     * @param id  商品主键
     * @param qty 增量（已支付数量）
     * @return 影响行数
     */
    @Update("UPDATE gz_ord_product "
        + "SET sales_count = sales_count + #{qty}, update_time = now(3) "
        + "WHERE id = #{id} AND del_flag = '0'")
    int increaseSalesCount(@Param("id") Long id, @Param("qty") long qty);
}
