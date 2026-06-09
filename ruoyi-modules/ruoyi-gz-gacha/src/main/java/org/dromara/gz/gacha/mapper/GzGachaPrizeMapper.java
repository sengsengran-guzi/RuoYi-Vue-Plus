package org.dromara.gz.gacha.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.gz.gacha.domain.entity.GzGachaPrize;

import java.util.List;
import java.util.Map;

/**
 * gz_gacha_prize 数据层（GZ-GACHA-101）。
 *
 * <p>多租户 / 软删 / 分页 / 乐观锁均由 ruoyi 拦截器自动处理（同 {@link GzGachaMachineMapper}）。</p>
 *
 * <p><b>GACHA-104 预留</b>：开盒事务的 {@code SELECT FOR UPDATE} 锁行 + 乐观锁库存扣减方法签名在本卡
 * <b>预置</b>（走 {@code idx_gacha_prize_pool} 索引高效锁），但仅供 GACHA-104 在 DB 事务内调用；本卡
 * 不在任何 service 路径触发（无事务上下文调用 FOR UPDATE 无意义）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-GACHA-101 / GACHA-104 预留)
 */
public interface GzGachaPrizeMapper extends BaseMapperPlus<GzGachaPrize, GzGachaPrize> {

    /**
     * 锁定某机器在池有货候选奖品行（GACHA-104 开盒事务步骤 2，doc/10 §8.N6）。
     *
     * <p><b>必须在 {@code @Transactional} 事务内调用</b>（{@code FOR UPDATE} 行锁随事务释放）；走
     * {@code idx_gacha_prize_pool (tenant_id, machine_id, enabled, stock_remain)} 索引。
     * 本卡仅预置签名供 GACHA-104，自身不调用。多租户由拦截器对注解 SQL 自动 append tenant 条件。</p>
     *
     * @param machineId 机器主键
     * @return 在池（enabled=1 且 stock_remain>0）有货候选奖品（已加行锁）
     */
    @Select("SELECT * FROM gz_gacha_prize "
        + "WHERE machine_id = #{machineId} AND enabled = 1 AND stock_remain > 0 AND del_flag = '0' "
        + "FOR UPDATE")
    List<GzGachaPrize> selectInPoolForUpdate(@Param("machineId") Long machineId);

    /**
     * 乐观锁扣减单个奖品库存（GACHA-104 开盒事务步骤 5，doc/10 §8.N6）。
     *
     * <p>{@code affected=0}（被并发抢空）→ GACHA-104 把该奖品移出候选 + 重新归一化重抽（有界，绝不退款）。
     * 本卡仅预置签名供 GACHA-104，自身不调用。</p>
     *
     * @param id      奖品主键
     * @param version 期望版本（乐观锁）
     * @return 影响行数（1=扣减成功 / 0=并发冲突或已抢空）
     */
    @Update("UPDATE gz_gacha_prize "
        + "SET stock_remain = stock_remain - 1, version = version + 1, update_time = now(3) "
        + "WHERE id = #{id} AND version = #{version} AND stock_remain > 0 AND del_flag = '0'")
    int deductStock(@Param("id") Long id, @Param("version") int version);

    /**
     * 批量统计多台机器「在池奖品总剩余库存」（GZ-GACHA-102 mp 列表 stockRemainSum，避免 N+1）。
     *
     * <p>口径：{@code SUM(stock_remain)} WHERE {@code enabled=1}（运营临停的奖品不计入剩余）；
     * 走 {@code idx_gacha_prize_pool (tenant_id, machine_id, enabled, stock_remain)} 索引。
     * 多租户由 ruoyi 拦截器对注解 SQL 自动 append {@code tenant_id} 条件；{@code @TableLogic} 不作用于
     * 原生注解 SQL，故显式 {@code del_flag='0'} 过滤软删奖品。</p>
     *
     * <p>无奖品 / 全部 enabled=0 的机器不在返回结果中（service 层取不到 → 视为 0）。</p>
     *
     * @param machineIds 机器主键列表（非空；空列表由 service 短路不调用）
     * @return 每行 {@code {machineId, stockSum}}（machineId BIGINT, stockSum BIGINT）
     */
    @Select("<script>"
        + "SELECT machine_id AS machineId, COALESCE(SUM(stock_remain), 0) AS stockSum "
        + "FROM gz_gacha_prize "
        + "WHERE enabled = 1 AND del_flag = '0' AND machine_id IN "
        + "<foreach collection='machineIds' item='mid' open='(' separator=',' close=')'>#{mid}</foreach> "
        + "GROUP BY machine_id"
        + "</script>")
    List<Map<String, Object>> sumStockRemainByMachineIds(@Param("machineIds") List<Long> machineIds);
}
