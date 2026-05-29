package org.dromara.gz.bean.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.gz.bean.domain.entity.GzBeanBooking;
import org.dromara.gz.bean.domain.vo.GzBeanBookingVO;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * gz_bean_booking 数据层（GZ-BEAN-004）。
 *
 * <p>多租户 / 软删由 ruoyi 拦截器自动处理；本接口仅暴露少量直查方法：</p>
 * <ul>
 *   <li>{@link #selectOccupiedSeatIds} — mp /availability 查指定时段已占用 seat_id 列表（接力 BEAN-003 留位）</li>
 *   <li>{@link #countActiveUserBooking} — 应用层校验"同一用户同时段最多 1 pending"（doc/10 §3 并发 / 业务校验）</li>
 * </ul>
 *
 * @author kevin-coder (sensenran-guzi · GZ-BEAN-004)
 */
public interface GzBeanBookingMapper extends BaseMapperPlus<GzBeanBooking, GzBeanBookingVO> {

    /**
     * 查指定门店 × 日期 × 时段开始 已被占用（status='pending'）的 seat_id 列表。
     *
     * <p>用于 mp /availability 端点判断座位是否可选（BEAN-003 留位接口本 ticket 接力）。</p>
     *
     * @param tenantId  租户 ID
     * @param storeId   门店 ID
     * @param sessDate  预约日期
     * @param slotStart 时段开始时间
     * @return 已占用的 seat_id 列表
     */
    @Select("SELECT seat_id FROM gz_bean_booking " +
        "WHERE tenant_id = #{tenantId} AND store_id = #{storeId} " +
        "  AND sess_date = #{sessDate} AND slot_start = #{slotStart} " +
        "  AND status = 'pending' AND del_flag = '0'")
    List<Long> selectOccupiedSeatIds(@Param("tenantId") String tenantId,
                                     @Param("storeId") Long storeId,
                                     @Param("sessDate") LocalDate sessDate,
                                     @Param("slotStart") LocalTime slotStart);

    /**
     * 统计同一用户同门店同时段已有 pending 预约数（应用层校验"同用户同时段最多 1 个 active"）。
     *
     * @return 0 = 无活跃预约 / >=1 = 已有
     */
    @Select("SELECT COUNT(*) FROM gz_bean_booking " +
        "WHERE tenant_id = #{tenantId} AND user_id = #{userId} " +
        "  AND store_id = #{storeId} AND sess_date = #{sessDate} AND slot_start = #{slotStart} " +
        "  AND status = 'pending' AND del_flag = '0'")
    long countActiveUserBooking(@Param("tenantId") String tenantId,
                                @Param("userId") Long userId,
                                @Param("storeId") Long storeId,
                                @Param("sessDate") LocalDate sessDate,
                                @Param("slotStart") LocalTime slotStart);
}
