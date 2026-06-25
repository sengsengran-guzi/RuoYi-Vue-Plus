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
 * gz_bean_booking 数据层（GZ-BEAN-004 / GZ-BEAN-017）。
 *
 * <p>多租户 / 软删由 ruoyi 拦截器自动处理；本接口仅暴露少量直查方法：</p>
 * <ul>
 *   <li>{@link #countActiveCoveringSlotForUpdate} — 逐格防超卖：区间重叠计数 FOR UPDATE（GZ-BEAN-017 / ADR-0011 §3）</li>
 *   <li>{@link #selectExpiredPendingIds} — no_show cron 扫"昨日及之前仍 pending"的预约 id（GZ-BEAN-009）</li>
 *   <li>{@link #markNoShow} — no_show cron 条件 UPDATE（status=pending 守卫，天然原子幂等，GZ-BEAN-009）</li>
 * </ul>
 *
 * @author kevin-coder (sensenran-guzi · GZ-BEAN-004 / GZ-BEAN-009 / GZ-BEAN-017)
 */
public interface GzBeanBookingMapper extends BaseMapperPlus<GzBeanBooking, GzBeanBookingVO> {

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

    // ============================================================
    //  GZ-BEAN-014 V1.2 付费模型：配额计数防超卖 + 余量查询 + 支付状态机
    // ============================================================

    /**
     * 逐格防超卖核心查询（GZ-BEAN-017 AC 2，doc/15a §A.2 + ADR-0011 §3）。
     *
     * <p>在下单<b>同一事务</b>内对单个 1h 格 {@code gi}（格起整点）统计<b>覆盖该格的活跃</b> booking 行数，
     * {@code FOR UPDATE} 悲观锁锁住覆盖该格的活跃行 → 并发下单逐格串行化比对配额。一行区间预约对它覆盖的
     * 每一个 1h 格各占 1 配额，service 层对区间内每格各调一次本查询（按格升序加锁防交叠区间死锁，ADR-0011 §3）。</p>
     *
     * <p><b>「覆盖 gi」= 区间重叠，不是 slot_start 相等</b>（ADR-0011 §3）：一个 14:00–17:00 的活跃单确实占了
     * 15:00 这格，但它的 slot_start≠15:00。重叠条件钉死为 {@code slot_start <= gi AND slot_end > gi}
     * （gi 为格起整点，左闭右开）。</p>
     *
     * <p><b>活跃定义（doc/11 §3.6 钉死，不变）</b>：{@code status='pending' AND pay_status IN ('paying','paid')}
     * —— 付款中 / 已付的待核销单占名额；{@code used / cancelled / no_show / pay_closed / refunded} 全部释放不计数。</p>
     *
     * <p><b>tenant_id 显式传</b>：mp 下单事务用户态 JWT 无 tenant，不依赖 ruoyi 拦截器自动注入
     * （同 submit 注释），由 service 从 store / user 取 tenant 显式传入。</p>
     *
     * @param slot 1h 格起整点 gi（service 把下单区间按 1h 展开后逐格传入）
     * @return 覆盖该格的当前活跃 booking 数（与 gz_bean_seat_type_config.quantity 比对）
     */
    @Select("SELECT COUNT(*) FROM gz_bean_booking " +
        "WHERE tenant_id = #{tenantId} AND store_id = #{storeId} AND seat_type_config_id = #{seatTypeConfigId} " +
        "  AND sess_date = #{sessDate} AND slot_start <= #{slot} AND slot_end > #{slot} " +
        "  AND status = 'pending' AND pay_status IN ('paying','paid') AND del_flag = '0' " +
        "FOR UPDATE")
    long countActiveCoveringSlotForUpdate(@Param("tenantId") String tenantId,
                                          @Param("storeId") Long storeId,
                                          @Param("seatTypeConfigId") Long seatTypeConfigId,
                                          @Param("sessDate") LocalDate sessDate,
                                          @Param("slot") LocalTime slot);

    /**
     * 余量查询（GZ-BEAN-017 AC 1，无锁 — mp 选座实时显「可约/已满」用）。
     *
     * <p>对某门店某日某 1h 格 {@code gi} 统计<b>覆盖该格的活跃</b> booking 数（重叠 + 活跃定义同
     * {@link #countActiveCoveringSlotForUpdate}，但<b>不加 FOR UPDATE</b>，仅展示用）。
     * 余量 = {@code gz_bean_seat_type_config.quantity − 本计数}（service 层做减法，只把 full 布尔给 mp）。</p>
     *
     * @param slot 1h 格起整点 gi
     * @return 覆盖该格的当前活跃 booking 数
     */
    @Select("SELECT COUNT(*) FROM gz_bean_booking " +
        "WHERE tenant_id = #{tenantId} AND store_id = #{storeId} AND seat_type_config_id = #{seatTypeConfigId} " +
        "  AND sess_date = #{sessDate} AND slot_start <= #{slot} AND slot_end > #{slot} " +
        "  AND status = 'pending' AND pay_status IN ('paying','paid') AND del_flag = '0'")
    long countActiveCoveringSlot(@Param("tenantId") String tenantId,
                                 @Param("storeId") Long storeId,
                                 @Param("seatTypeConfigId") Long seatTypeConfigId,
                                 @Param("sessDate") LocalDate sessDate,
                                 @Param("slot") LocalTime slot);

    /**
     * 同用户同 (类型,日期) 已有与 {@code [reqStart, reqEnd)} <b>区间重叠</b>的活跃 booking 数（幂等校验，doc/10 §11 Q11.3）。
     *
     * <p>区间模型下「同一时段」= 区间重叠（两区间相交 ⟺ {@code slot_start < reqEnd AND slot_end > reqStart}）。
     * 活跃同上（status=pending AND pay_status IN paying/paid）。{@code >0} 则该用户已占重叠时段名额，
     * service 层拒单（防同用户重复占配额）。</p>
     */
    @Select("SELECT COUNT(*) FROM gz_bean_booking " +
        "WHERE tenant_id = #{tenantId} AND user_id = #{userId} AND store_id = #{storeId} " +
        "  AND seat_type_config_id = #{seatTypeConfigId} AND sess_date = #{sessDate} " +
        "  AND slot_start < #{reqEnd} AND slot_end > #{reqStart} " +
        "  AND status = 'pending' AND pay_status IN ('paying','paid') AND del_flag = '0'")
    long countActiveUserOverlap(@Param("tenantId") String tenantId,
                                @Param("userId") Long userId,
                                @Param("storeId") Long storeId,
                                @Param("seatTypeConfigId") Long seatTypeConfigId,
                                @Param("sessDate") LocalDate sessDate,
                                @Param("reqStart") LocalTime reqStart,
                                @Param("reqEnd") LocalTime reqEnd);

    /**
     * 按 out_trade_no 查 booking（支付回调 onPaid 用 business_order_no=booking_no 定位，此辅以 out_trade_no 校验）。
     */
    @Select("SELECT * FROM gz_bean_booking WHERE out_trade_no = #{outTradeNo} AND del_flag = '0' LIMIT 1")
    GzBeanBooking selectByOutTradeNo(@Param("outTradeNo") String outTradeNo);

    /**
     * 支付成功条件 UPDATE（onPaid，GZ-BEAN-014 AC 5）：{@code pay_status: paying → paid} + 写 verify_code。
     *
     * <p>WHERE 含 {@code pay_status='paying'} 守卫 → 幂等：重复回调 / 已 paid 的单 affected=0（PAY-101 SPI
     * 已保证至多调一次，此处再加守卫双保险，doc/10 §11.N9）。</p>
     *
     * @return 受影响行数（1 = 推进成功 / 0 = 已非 paying，幂等跳过）
     */
    @Update("UPDATE gz_bean_booking " +
        "SET pay_status = 'paid', verify_code = #{verifyCode} " +
        "WHERE id = #{id} AND pay_status = 'paying' AND del_flag = '0'")
    int markPaid(@Param("id") Long id, @Param("verifyCode") String verifyCode);

    /**
     * 支付关闭条件 UPDATE（pay_closed，GZ-BEAN-014 AC 5）：{@code pay_status: → pay_closed} +
     * {@code status: → cancelled}（同步释放配额，ADR-0007 §1.5）。
     *
     * <p>WHERE 含 {@code pay_status IN ('unpaid','paying')} 守卫 → 仅未完成支付的单可关闭（已 paid 的不动）；
     * 幂等：已 pay_closed / paid 的单 affected=0。同步把 status 推到 cancelled + 写 cancelled_time，
     * 释放该 (类型,时段) 配额名额（status 离 pending 即不计活跃）。</p>
     *
     * @return 受影响行数（1 = 关闭成功 / 0 = 已非 unpaid/paying，幂等跳过）
     */
    @Update("UPDATE gz_bean_booking " +
        "SET pay_status = 'pay_closed', status = 'cancelled', cancelled_time = #{closedTime} " +
        "WHERE id = #{id} AND pay_status IN ('unpaid','paying') AND status = 'pending' AND del_flag = '0'")
    int markPayClosed(@Param("id") Long id, @Param("closedTime") LocalDateTime closedTime);

    /**
     * 退款回写条件 UPDATE（D16 P2，admin 全额退款回调触发）：{@code pay_status: paid → refunded}。
     *
     * <p>{@code status} 仅当仍 {@code pending}（未核销）时同步 → {@code cancelled} 释放该 (类型,时段) 配额名额
     * （活跃口径 status='pending' AND pay_status IN(paying,paid)，离开即释放）；已 {@code used}（已核销消费）的单
     * 保留 status=used（退的是已消费单，不改业务态，仅记 pay_status=refunded）。WHERE 含 {@code pay_status='paid'}
     * 守卫 → 幂等（重复回调 affected=0）。</p>
     *
     * @return 受影响行数（1 = 回写成功 / 0 = 已非 paid，幂等跳过）
     */
    @Update("UPDATE gz_bean_booking " +
        "SET pay_status = 'refunded', " +
        "    status = IF(status = 'pending', 'cancelled', status), " +
        "    cancelled_time = IF(status = 'pending', #{refundedTime}, cancelled_time) " +
        "WHERE id = #{id} AND pay_status = 'paid' AND del_flag = '0'")
    int markRefunded(@Param("id") Long id, @Param("refundedTime") LocalDateTime refundedTime);

    /**
     * 扫超时未付的活跃占位单（GZ-BEAN-014 AC 8 unpaid 超时回收，doc/10 §11.N13a）。
     *
     * <p>{@code pay_status IN ('unpaid','paying') AND status='pending' AND create_time < 截止时间}
     * —— 下单后超 N 分钟仍未支付成功（占而不付）。由 SnailJob 逐条 {@link #markPayClosed} 释放配额。</p>
     *
     * @param deadline 截止时间（now − 超时分钟数），create_time 早于此的视为超时
     * @return 待回收的 booking id 列表
     */
    @Select("SELECT id FROM gz_bean_booking " +
        "WHERE pay_status IN ('unpaid','paying') AND status = 'pending' " +
        "  AND create_time < #{deadline} AND del_flag = '0' ORDER BY id")
    List<Long> selectExpiredUnpaidIds(@Param("deadline") LocalDateTime deadline);
}
