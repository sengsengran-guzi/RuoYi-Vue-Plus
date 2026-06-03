package org.dromara.gz.ord.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.gz.ord.domain.entity.GzOrdSku;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * gz_ord_sku 数据层（GZ-ORD-101）。
 *
 * <p>多租户 / 软删 / 分页由 ruoyi 拦截器自动注入。库存扣减用自定义 SQL（{@code @Version} 自动机制
 * 无法表达 {@code stock_remain IS NULL OR >= ?} 的无限库存分支，doc/11 §6.2）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-ORD-101)
 */
public interface GzOrdSkuMapper extends BaseMapperPlus<GzOrdSku, GzOrdSku> {

    /**
     * SKU 库存乐观锁扣减（GZ-ORD-101 AC 4，<b>SQL 逐字照 doc/11 §6.2 扣减口径</b> + doc/10 §7.N6）。
     *
     * <pre>
     * UPDATE gz_ord_sku SET stock_remain = stock_remain - ?, version = version + 1
     * WHERE id = ? AND (stock_remain IS NULL OR stock_remain >= ?) AND version = ?
     * </pre>
     *
     * <p><b>无限库存（stock_remain IS NULL）</b>：{@code NULL - qty} 结果仍为 NULL（MySQL NULL 算术语义），
     * 故无限库存扣后仍 NULL —— 永不耗尽（强约束 #3）。{@code (stock_remain IS NULL OR stock_remain >= ?)}
     * 保证无限库存分支条件恒成立。</p>
     *
     * <p><b>影响行数</b>：1 = 扣减成功（含无限库存）；0 = 库存不足 或 version 并发冲突（业务层重试 ≤3 后抛
     * {@code SKU_OUT_OF_STOCK}）。多租户 + 软删由拦截器对注解 SQL 自动 append（tenant_id + del_flag='0'）。</p>
     *
     * @param id      SKU 主键
     * @param qty     扣减数量（> 0，调用方保证）
     * @param version 期望版本（乐观锁；调用方先查得当前 version 再传入）
     * @return 影响行数（1 成功 / 0 库存不足或冲突）
     */
    @Update("UPDATE gz_ord_sku "
        + "SET stock_remain = stock_remain - #{qty}, version = version + 1 "
        + "WHERE id = #{id} AND (stock_remain IS NULL OR stock_remain >= #{qty}) AND version = #{version} "
        + "AND del_flag = '0'")
    int tryDeductStock(@Param("id") Long id, @Param("qty") int qty, @Param("version") int version);

    /**
     * 库存归还（退款 / 取消时调用；ORD-104 / PAY-103 接入，本 ticket 预置）。
     *
     * <p>无限库存（stock_remain IS NULL）不归还（NULL + qty 仍 NULL，语义一致）。</p>
     *
     * @param id  SKU 主键
     * @param qty 归还数量
     * @return 影响行数
     */
    @Update("UPDATE gz_ord_sku "
        + "SET stock_remain = stock_remain + #{qty}, version = version + 1 "
        + "WHERE id = #{id} AND stock_remain IS NOT NULL AND del_flag = '0'")
    int returnStock(@Param("id") Long id, @Param("qty") int qty);

    /**
     * 批量查多商品起始价（GZ-ORD-102 AC2 强约束 #4）：每个 product_id 取 enabled=1 SKU 的 MIN(price_cent)。
     *
     * <p>一次 GROUP BY 查全部，避免 mp 列表 N+1（每卡一次 min 查询）。多租户 + 软删由 ruoyi 拦截器对
     * 注解 SQL 自动 append（tenant_id + del_flag='0'），故此处不显式写。返回 {@code {productId, minPrice}}
     * Map 列表，service 转 {@code Map<Long,Long>}；无 enabled SKU 的商品不在结果中（卡片 startPriceCent → null）。</p>
     *
     * @param productIds 商品主键集合（调用方保证非空）
     * @return 每行 {@code {productId: Long, minPrice: Long}}
     */
    @Select("<script>"
        + "SELECT product_id AS productId, MIN(price_cent) AS minPrice "
        + "FROM gz_ord_sku "
        + "WHERE enabled = 1 AND product_id IN "
        + "<foreach collection='productIds' item='pid' open='(' separator=',' close=')'>#{pid}</foreach> "
        + "GROUP BY product_id"
        + "</script>")
    List<Map<String, Object>> selectMinPriceByProductIds(@Param("productIds") Collection<Long> productIds);
}
