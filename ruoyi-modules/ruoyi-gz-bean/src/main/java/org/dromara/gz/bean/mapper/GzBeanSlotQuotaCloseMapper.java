package org.dromara.gz.bean.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.gz.bean.domain.entity.GzBeanSlotQuotaClose;
import org.dromara.gz.bean.domain.vo.GzBeanSlotQuotaCloseVO;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * gz_bean_slot_quota_close 数据层（客户 0702 反馈 #4a）。
 *
 * <p>admin CRUD 走 BaseMapperPlus 默认方法（多租户 / 软删由 ruoyi 拦截器自动处理）。
 * 额外自定义两个查询：单格 close_count 取值（余量扣减 + upsert 命中判断），供
 * {@code selectTypeSlotAvailability / *Detail} 复用。</p>
 *
 * @author kevin-coder (sensenran-guzi · 客户 0702 反馈 #4a)
 */
public interface GzBeanSlotQuotaCloseMapper extends BaseMapperPlus<GzBeanSlotQuotaClose, GzBeanSlotQuotaCloseVO> {

    /**
     * 某门店某桌型某具体服务日在 1h 格 {@code slotStart} 已配置的关闭配额个数（generated column，含 0 / 未配置=0）。
     *
     * <p><b>tenant_id 显式传</b>：mp 下单 / 余量接口用户态 JWT 无可靠 tenant，不依赖拦截器自动注入
     * （对齐 {@link GzBeanSeatClosureMapper#countClosedSeatsCoveringSlot} 注释），由 service 从 store 取 tenant 显式传入。
     * 未命中（无配置行）返回 {@code null}，service 端 {@code Optional.orElse(0)} 归零。</p>
     *
     * @param tenantId         租户
     * @param storeId          门店
     * @param seatTypeConfigId 桌型档
     * @param sessDate         服务日
     * @param slotStart        1h 格起整点
     * @return 该格关闭配额个数（未配置返回 null）
     */
    @Select("SELECT close_count FROM gz_bean_slot_quota_close " +
        "WHERE tenant_id = #{tenantId} AND store_id = #{storeId} " +
        "  AND seat_type_config_id = #{seatTypeConfigId} " +
        "  AND sess_date = #{sessDate} AND slot_start = #{slotStart} " +
        "  AND del_flag = '0' LIMIT 1")
    Integer selectCloseCount(@Param("tenantId") String tenantId,
                             @Param("storeId") Long storeId,
                             @Param("seatTypeConfigId") Long seatTypeConfigId,
                             @Param("sessDate") LocalDate sessDate,
                             @Param("slotStart") LocalTime slotStart);

    /**
     * 命中唯一键（tenant/store/config/date/slot）的现存配额关闭行 id（含软删=0），供 upsert 判断改 or 建。
     *
     * @return 现存行 id（未命中返回 null）
     */
    @Select("SELECT id FROM gz_bean_slot_quota_close " +
        "WHERE tenant_id = #{tenantId} AND store_id = #{storeId} " +
        "  AND seat_type_config_id = #{seatTypeConfigId} " +
        "  AND sess_date = #{sessDate} AND slot_start = #{slotStart} " +
        "  AND del_flag = '0' LIMIT 1")
    Long selectExistingId(@Param("tenantId") String tenantId,
                          @Param("storeId") Long storeId,
                          @Param("seatTypeConfigId") Long seatTypeConfigId,
                          @Param("sessDate") LocalDate sessDate,
                          @Param("slotStart") LocalTime slotStart);
}
