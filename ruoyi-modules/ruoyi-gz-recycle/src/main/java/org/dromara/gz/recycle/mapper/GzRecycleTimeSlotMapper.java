package org.dromara.gz.recycle.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.time.LocalTime;
import org.apache.ibatis.annotations.Update;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.gz.recycle.domain.entity.GzRecycleTimeSlot;

/**
 * gz_recycle_time_slot 数据层（GZ-RECYCLE-006）。
 *
 * <p>多租户由 ruoyi {@code TenantLineInnerInterceptor} 自动 append {@code WHERE tenant_id = ?}；
 * 软删由 {@code @TableLogic} 自动过滤；分页由 {@code PaginationInnerInterceptor} 注入。
 * 查询条件全走 {@code LambdaQueryWrapper} 在 service 组装（不写 XML），便于单测 mock。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-RECYCLE-006)
 */
public interface GzRecycleTimeSlotMapper extends BaseMapperPlus<GzRecycleTimeSlot, GzRecycleTimeSlot> {

    /**
     * 跨表轻量查 {@code gz_bean_store.name}（recycle 不依赖 gz-bean 实体 → 原生 SQL 仅取名字段，
     * 与 {@code GzRecycleAppointmentMapper.selectStoreNameById} 同口径），admin 列表回显门店名。
     *
     * @param storeId 门店 id
     * @return 门店名；查不到返 null
     */
    @Select("SELECT name FROM gz_bean_store WHERE id = #{storeId} AND del_flag = '0' LIMIT 1")
    String selectStoreNameById(@Param("storeId") Long storeId);

    /**
     * 按 {@code (store, start, end)} 裸查（<b>绕过 {@code @TableLogic}</b>，含软删行），GZ-RECYCLE-012。
     *
     * <p>{@code uk_tenant_store_time(tenant_id, store_id, start_time, end_time)} <b>不含 del_flag</b>，
     * 软删行仍占唯一键，但走 MP 的常规查询看不见它 → 直接 insert 会吃 DB 1062 变 500。
     * service 用本查询探测后走「复活」而不是「新建」。</p>
     */
    @Select("SELECT * FROM gz_recycle_time_slot " +
        "WHERE store_id = #{storeId} AND start_time = #{startTime} AND end_time = #{endTime} LIMIT 1")
    GzRecycleTimeSlot selectRawByWindow(@Param("storeId") Long storeId,
                                        @Param("startTime") LocalTime startTime,
                                        @Param("endTime") LocalTime endTime);

    /** 复活软删行并覆盖可编辑字段（GZ-RECYCLE-012；对 admin 而言效果等同新建）。 */
    @Update("UPDATE gz_recycle_time_slot " +
        "SET del_flag = '0', label = #{label}, enabled = #{enabled}, sort_no = #{sortNo}, " +
        "    remark = #{remark}, update_time = NOW() " +
        "WHERE id = #{id}")
    int reviveSoftDeleted(@Param("id") Long id,
                          @Param("label") String label,
                          @Param("enabled") Integer enabled,
                          @Param("sortNo") Integer sortNo,
                          @Param("remark") String remark);
}
