package org.dromara.gz.recycle.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
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
}
