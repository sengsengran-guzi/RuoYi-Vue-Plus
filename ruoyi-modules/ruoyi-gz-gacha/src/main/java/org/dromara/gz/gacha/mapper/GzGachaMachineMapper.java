package org.dromara.gz.gacha.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.gz.gacha.domain.entity.GzGachaMachine;

/**
 * gz_gacha_machine 数据层（GZ-GACHA-101）。
 *
 * <p>多租户由 ruoyi {@code TenantLineInnerInterceptor} 自动 append {@code WHERE tenant_id = ?}；
 * 软删由 {@code @TableLogic} 自动过滤；分页由 {@code PaginationInnerInterceptor} 注入；
 * 乐观锁由 {@code OptimisticLockerInnerInterceptor} 对 updateById 生效。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-GACHA-101)
 */
public interface GzGachaMachineMapper extends BaseMapperPlus<GzGachaMachine, GzGachaMachine> {

    /**
     * 累计抽奖次数累加（GACHA-104 开盒事务成功时调用；本卡预置原子 UPDATE，不读改写防并发丢失）。
     *
     * @param id  机器主键
     * @param qty 增量（本次抽奖次数，单抽=1 / 十连=10）
     * @return 影响行数
     */
    @Update("UPDATE gz_gacha_machine "
        + "SET sales_count = sales_count + #{qty}, update_time = now(3) "
        + "WHERE id = #{id} AND del_flag = '0'")
    int increaseSalesCount(@Param("id") Long id, @Param("qty") long qty);

    /**
     * 到点自动下架批量 UPDATE（GACHA-104 / cron 用；本卡仅预置签名 + 注解 SQL，调用留下游）。
     *
     * <p>{@code on_shelf AND offline_time < now()} → {@code auto_off}（决策 D5，唯一写 auto_off 路径之一）。
     * WHERE 二次校验 status='on_shelf' 保证幂等可重跑。</p>
     *
     * @return 实际自动下架行数
     */
    @Update("UPDATE gz_gacha_machine "
        + "SET status = 'auto_off', update_time = now(3) "
        + "WHERE status = 'on_shelf' AND offline_time IS NOT NULL AND offline_time < now(3) AND del_flag = '0'")
    int autoOffExpiredMachines();
}
