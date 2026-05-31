package org.dromara.gz.bean.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.gz.bean.domain.entity.GzBeanBooking;
import org.dromara.gz.bean.domain.vo.GzBeanBookingVO;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

/**
 * gz_bean_booking 数据层（GZ-BEAN-004）。
 *
 * <p>多租户 / 软删由 ruoyi 拦截器自动处理；本接口仅暴露少量直查方法：</p>
 * <ul>
 *   <li>{@link #selectOccupiedSeatIds} — mp /availability 查指定时段已占用 seat_id 列表（接力 BEAN-003 留位）</li>
 *   <li>{@link #countActiveUserBooking} — 应用层校验"同一用户同时段最多 1 pending"（doc/10 §3 并发 / 业务校验）</li>
 *   <li>{@link #selectExpiredPendingIds} — no_show cron 扫"昨日及之前仍 pending"的预约 id（GZ-BEAN-009）</li>
 *   <li>{@link #markNoShow} — no_show cron 条件 UPDATE（status=pending 守卫，天然原子幂等，GZ-BEAN-009）</li>
 * </ul>
 *
 * @author kevin-coder (sensenran-guzi · GZ-BEAN-004 / GZ-BEAN-009)
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

    /**
     * 扫"昨日及之前（sess_date &lt; CURDATE()）仍 pending"的预约 id 列表（GZ-BEAN-009 no_show cron）。
     *
     * <p>不分页全量取 id（V1.0 单日 pending 量极小），由 service 逐条条件 UPDATE 标记。
     * 多租户由 ruoyi 拦截器自动注入 tenant_id 条件，本 SQL 不显式写 tenant_id（保持与
     * cron 上下文一致；cron 无登录态 → service 层用 {@code TenantHelper.ignore} 全租户扫，见
     * IGzBeanBookingService#markNoShowBatch javadoc）。</p>
     *
     * @return 待标 no_show 的预约 id（sess_date &lt; CURDATE() AND status='pending'）
     */
    @Select("SELECT id FROM gz_bean_booking " +
        "WHERE status = 'pending' AND sess_date < CURDATE() AND del_flag = '0' " +
        "ORDER BY id")
    List<Long> selectExpiredPendingIds();

    /**
     * 条件 UPDATE 标记单条预约为 no_show（GZ-BEAN-009）。
     *
     * <p>WHERE 含 {@code status='pending'} 守卫 → 天然原子 + 幂等：</p>
     * <ul>
     *   <li>affected=1 → 本次成功标记（之前确为 pending）</li>
     *   <li>affected=0 → 已被并发 / 上轮 cron 改成 used/cancelled/no_show，跳过（幂等）</li>
     * </ul>
     *
     * <p>同步把 dedup_token 切到 booking_no（方案 C，与 verify/cancel 一致：终态释放座位时段占位）。
     * 不走 mybatis-plus @Version 乐观锁（条件 UPDATE 的 status 守卫已足够保证状态机原子推进）。</p>
     *
     * @param id        预约 id
     * @param noShowTime 标记时间
     * @return 受影响行数（1 = 标记成功 / 0 = 已非 pending，幂等跳过）
     */
    @Update("UPDATE gz_bean_booking " +
        "SET status = 'no_show', no_show_time = #{noShowTime}, dedup_token = booking_no " +
        "WHERE id = #{id} AND status = 'pending' AND del_flag = '0'")
    int markNoShow(@Param("id") Long id, @Param("noShowTime") LocalDateTime noShowTime);

    /**
     * 按 booking_no 查预约（GZ-BEAN-008 admin 扫码核销：解析 QR payload 拆出 bookingNo 后回表）。
     *
     * <p>多租户由 ruoyi 拦截器自动注入 tenant_id 条件（admin 登录态有 tenantId）；del_flag 软删过滤显式带。
     * V1.0 booking_no 在 (tenant_id, booking_no) 上 UNIQUE，至多 1 条。</p>
     *
     * @param bookingNo 业务码
     * @return 命中的预约（无则 null）
     */
    @Select("SELECT * FROM gz_bean_booking WHERE booking_no = #{bookingNo} AND del_flag = '0' LIMIT 1")
    GzBeanBooking selectByBookingNo(@Param("bookingNo") String bookingNo);
}
