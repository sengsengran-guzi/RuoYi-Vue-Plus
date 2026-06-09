package org.dromara.gz.gacha.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.gz.gacha.domain.entity.GzUserGachaCollection;

import java.util.List;

/**
 * gz_user_gacha_collection 数据层（GZ-GACHA-104 + GACHA-107 图鉴查询扩展）。
 *
 * <p>开盒事务内 UPSERT 图鉴 +1（doc/10 §8.N9，GACHA-104）；GACHA-107 图鉴消费查询：
 * distinct machine（分组）+ 按 user×machine 查行（已得态映射，走 MyBatis-Plus 内置 selectList）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-GACHA-104 / GACHA-107)
 */
public interface GzUserGachaCollectionMapper extends BaseMapperPlus<GzUserGachaCollection, GzUserGachaCollection> {

    /**
     * 图鉴 UPSERT +1（开盒事务步骤 5，doc/10 §8.N9）。
     *
     * <p>按 {@code uk_user_gacha_collection (tenant_id, user_id, machine_id, prize_id)} 命中 →
     * {@code drawn_count+1}（first_drawn_time 不动）；未命中 → INSERT {@code drawn_count=1} +
     * {@code first_drawn_time=NOW}。{@code tenant_id} <b>显式传入</b>（@Async 开盒线程无自动租户上下文，
     * 且 mp 用户 JWT extra 无 tenantId —— 取自 {@code gz_user.tenant_id}，与 gz-bean 同款，
     * 不依赖 mybatis-plus 自动填充 / 拦截器注入）。</p>
     *
     * @param tenantId  租户 ID（取自 gz_user.tenant_id）
     * @param userId    用户 id
     * @param machineId 机器 id
     * @param prizeId   获得物 id
     * @return 受影响行数（插入=1 / +1 更新=2，MySQL ON DUPLICATE KEY UPDATE 语义）
     */
    @Update("INSERT INTO gz_user_gacha_collection "
        + "(tenant_id, user_id, machine_id, prize_id, first_drawn_time, drawn_count, version, create_time, update_time, del_flag) "
        + "VALUES (#{tenantId}, #{userId}, #{machineId}, #{prizeId}, NOW(3), 1, 0, NOW(3), NOW(3), '0') "
        + "ON DUPLICATE KEY UPDATE "
        + "drawn_count = drawn_count + 1, update_time = NOW(3), del_flag = '0'")
    int upsertCollection(@Param("tenantId") String tenantId,
                         @Param("userId") Long userId,
                         @Param("machineId") Long machineId,
                         @Param("prizeId") Long prizeId);

    /**
     * 查用户图鉴中出现过的 distinct machine_id（GACHA-107 分组，doc/12 §MP-ME-COLLECTION）。
     *
     * <p>仅返回用户实际收集过的机器（决策 R2：不全量拉平台机器，控接口体积）；按机器 id 升序稳定。
     * 多租户由 ruoyi {@code TenantLineInnerInterceptor} 对注解 SQL 自动 append {@code tenant_id} 条件；
     * {@code @TableLogic} 不作用于原生注解 SQL，故显式 {@code del_flag='0'} 过滤软删行。</p>
     *
     * @param userId 用户 id
     * @return 该用户收集过的 distinct 机器 id（升序；无收集 → 空 list）
     */
    @Select("SELECT DISTINCT machine_id FROM gz_user_gacha_collection "
        + "WHERE user_id = #{userId} AND del_flag = '0' "
        + "ORDER BY machine_id ASC")
    List<Long> selectDistinctMachineIds(@Param("userId") Long userId);
}
