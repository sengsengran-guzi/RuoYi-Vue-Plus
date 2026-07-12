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
     * <p><b>口径（甲方「预定时段过完未到店即自动释放座位」，grace=0 按时间段而非时长）</b>：扫所有
     * 「整个预约时段已过完（{@code TIMESTAMP(sess_date, slot_end) <= NOW()}）仍 pending」的单。
     * 含今日早场已结束的格 + 所有历史日。标 no_show 即把 dedup_token 切 booking_no 释放座位占位，
     * 让该座该格立刻可被同日后续时段再约。配套高频 cron（每 5 分钟，见 GzBeanNoShowMarkJob javadoc），
     * 故座位在 slot_end 后 ≤ 5 分钟内释放。NOW() / sess_date / slot_end 均容器本地时区 Asia/Shanghai。</p>
     *
     * @return 待标 no_show 的预约 id（{@code status='pending' AND TIMESTAMP(sess_date, slot_end) <= NOW()}）
     */
    @Select("SELECT id FROM gz_bean_booking " +
        "WHERE status = 'pending' AND TIMESTAMP(sess_date, slot_end) <= NOW() AND del_flag = '0' " +
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
        "SET status = 'no_show', no_show_time = #{noShowTime}, dedup_token = booking_no, " +
        "    seat_id = NULL, seat_no_snapshot = NULL " +
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
     * <p><b>「覆盖 gi」= 区间与格 [gi, gi+1h) 重叠，不是 slot_start 相等</b>（ADR-0011 §3；GZ-BEAN-046 分钟精度修正）：
     * 一个 14:00–17:00 的活跃单确实占了 15:00 这格，但它的 slot_start≠15:00。重叠条件 =
     * {@code slot_start < gi+1h AND COALESCE(actual_end_slot, slot_end) > gi}（gi 为格起整点，格 = 左闭右开 [gi,gi+1h)；
     * 止界用 COALESCE 与放座即空一致，提前放座后该格立即释放）。<b>起界用 {@code slot_start < gi+1h}（非旧 {@code slot_start <= gi}）</b>：
     * 对整点单二者恒等（slot_start 是整点，{@code <=gi} ⟺ {@code <gi+1h}），是 no-op；但对代客预约的<b>分钟精度</b>单
     * （GZ-BEAN-046，如 16:34 起）唯有 {@code < gi+1h} 才能把它正确计入其所在整点格（16:00 格：16:34&lt;17:00），
     * 保证 mp 线上同桌型余量不被分钟起点漏算、不超卖（Kevin 拍板「按整点格保护」）。</p>
     *
     * <p><b>活跃定义（ADR-0016 §2）</b>：
     * {@code status IN ('pending','used') AND pay_status IN ('paying','paid')} —— pending（待到店占计划格）
     * 与 used（已核销占走 1 个物理座）<b>都计配额</b>；{@code cancelled / no_show / pay_closed / refunded} 全部释放不计。
     * <b>⚠️ 反转后铁律</b>：ADR-0016 下「核销才占物理座」，若配额只数 pending（漏 used），used 单退出配额却仍占物理座
     * → 下单层超卖（付款后无座可分）。故配额口径必须含 used，与座位互斥同口径（取代 GZ-BEAN-017 旧 pending-only 口径，
     * 旧口径仅在「具体座位互斥防超卖、配额只作展示」的 ADR-0015 下成立）。</p>
     *
     * <p><b>tenant_id 显式传</b>：mp 下单事务用户态 JWT 无 tenant，不依赖 ruoyi 拦截器自动注入
     * （同 submit 注释），由 service 从 store / user 取 tenant 显式传入。</p>
     *
     * @param slot 1h 格起整点 gi（service 把下单区间按 1h 展开后逐格传入）
     * @return 覆盖该格的当前活跃 booking 数（与 slotCapacity = 物理座位数 比对）
     */
    @Select("SELECT COUNT(*) FROM gz_bean_booking " +
        "WHERE tenant_id = #{tenantId} AND store_id = #{storeId} AND seat_type_config_id = #{seatTypeConfigId} " +
        "  AND sess_date = #{sessDate} AND slot_start < ADDTIME(#{slot}, '01:00:00') AND COALESCE(actual_end_slot, slot_end) > #{slot} " +
        "  AND status IN ('pending','used') AND pay_status IN ('paying','paid') AND del_flag = '0' " +
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
        "  AND sess_date = #{sessDate} AND slot_start < ADDTIME(#{slot}, '01:00:00') AND COALESCE(actual_end_slot, slot_end) > #{slot} " +
        "  AND status IN ('pending','used') AND pay_status IN ('paying','paid') AND del_flag = '0'")
    long countActiveCoveringSlot(@Param("tenantId") String tenantId,
                                 @Param("storeId") Long storeId,
                                 @Param("seatTypeConfigId") Long seatTypeConfigId,
                                 @Param("sessDate") LocalDate sessDate,
                                 @Param("slot") LocalTime slot);

    /**
     * 包天名额 cap 防超卖计数（GZ-BEAN-042 / ADR-0017）：某门店某日某桌型档<b>活跃包天单</b>数
     * （{@code is_day_pass=1}），{@code FOR UPDATE} 悲观锁。
     *
     * <p><b>并发前提</b>：与逐格 count 同模型 —— 仅 {@code REPEATABLE_READ} 下 InnoDB 对
     * {@code idx_gz_bean_booking_daypass (tenant,store,config,date,is_day_pass)} 窄索引区段加间隙锁串行化并发
     * 包天下单（COUNT 命中 0 行也锁区段挡并发 INSERT 后读旧 count），下单事务 {@code isolation=REPEATABLE_READ}
     * 锁死该前提。cap 检查须放逐格 count <b>之前</b>（统一加锁顺序，与小时单只做逐格无交叉死锁）。</p>
     *
     * <p><b>活跃口径必须与 {@link #countActiveCoveringSlotForUpdate} 完全一致</b>：
     * {@code status IN ('pending','used') AND pay_status IN ('paying','paid')}。放座（写 actual_end_slot）
     * 的包天单仍 {@code status='used'} → 仍占名额（当日已售，早退不还名额）。</p>
     *
     * @return 当日该桌型档活跃包天单数（与 config.day_pass_quota 比对）
     */
    @Select("SELECT COUNT(*) FROM gz_bean_booking " +
        "WHERE tenant_id = #{tenantId} AND store_id = #{storeId} AND seat_type_config_id = #{seatTypeConfigId} " +
        "  AND sess_date = #{sessDate} AND is_day_pass = 1 " +
        "  AND status IN ('pending','used') AND pay_status IN ('paying','paid') AND del_flag = '0' " +
        "FOR UPDATE")
    long countActiveDayPassForUpdate(@Param("tenantId") String tenantId,
                                     @Param("storeId") Long storeId,
                                     @Param("seatTypeConfigId") Long seatTypeConfigId,
                                     @Param("sessDate") LocalDate sessDate);

    /**
     * 包天名额已售计数（GZ-BEAN-042 无锁版）：口径同 {@link #countActiveDayPassForUpdate}，
     * 但<b>不加 FOR UPDATE</b>，仅 day-pass-options 展示「是否售罄」用（{@code sold ≥ day_pass_quota → full}）。
     */
    @Select("SELECT COUNT(*) FROM gz_bean_booking " +
        "WHERE tenant_id = #{tenantId} AND store_id = #{storeId} AND seat_type_config_id = #{seatTypeConfigId} " +
        "  AND sess_date = #{sessDate} AND is_day_pass = 1 " +
        "  AND status IN ('pending','used') AND pay_status IN ('paying','paid') AND del_flag = '0'")
    long countActiveDayPass(@Param("tenantId") String tenantId,
                            @Param("storeId") Long storeId,
                            @Param("seatTypeConfigId") Long seatTypeConfigId,
                            @Param("sessDate") LocalDate sessDate);

    // ============================================================
    //  GZ-BEAN-024 具体座位区间互斥（ADR-0015 §2 / doc/11 §3.6）—— seat-map 展示 + 改派冲突用
    //  （代客预约 walk-in 的座位判定 GZ-BEAN-046 已改为「当下物理占用」selectSeatOccupiedNowForUpdate，
    //   不再走本区间互斥；单座 FOR UPDATE 版 selectActiveSeatOverlapForUpdate 随之删除。）
    // ============================================================

    /**
     * seat-map 可用性：批量取某门店某日<b>每个具体座位</b>在请求区间 {@code [reqStart, reqEnd)} 内是否被占
     * （GZ-BEAN-024，无锁，仅展示用）。返回该日所有「与请求区间重叠的活跃单」所占的 {@code seat_id} 去重列表，
     * service 层据此对每座算 {@code full}（座 id ∈ 本列表 → full=true）。重叠 + 活跃 + 占用止界口径同
     * {@link #selectActiveSeatOverlapExcludingForUpdate}（{@code slot_start < reqEnd AND
     * COALESCE(actual_end_slot, slot_end) > reqStart}），但不加 {@code FOR UPDATE}、不限定单座、不排除自身。</p>
     *
     * @return 在请求区间内被占的座位 id 去重列表
     */
    @Select("SELECT DISTINCT seat_id FROM gz_bean_booking " +
        "WHERE tenant_id = #{tenantId} AND store_id = #{storeId} AND seat_id IS NOT NULL " +
        "  AND sess_date = #{sessDate} " +
        "  AND slot_start < #{reqEnd} AND COALESCE(actual_end_slot, slot_end) > #{reqStart} " +
        "  AND status IN ('pending','used') AND pay_status IN ('paying','paid') AND del_flag = '0'")
    List<Long> selectOccupiedSeatIds(@Param("tenantId") String tenantId,
                                     @Param("storeId") Long storeId,
                                     @Param("sessDate") LocalDate sessDate,
                                     @Param("reqStart") LocalTime reqStart,
                                     @Param("reqEnd") LocalTime reqEnd);

    /**
     * 核销分座「当下物理占用」判定（GZ-BEAN-043 / ADR-0017 §店员自主）：某具体座位 {@code seatId} 此刻是否有人在坐。
     *
     * <p><b>与区间/配额判定分家（关键）</b>：逐格配额 {@link #countActiveCoveringSlotForUpdate} 的
     * {@code [gi, gi+1h)} 格覆盖 + {@code actual_end_slot}（放座向上取整整点）止界是为<b>逐格配额防超卖</b>
     * 服务的——卖出的整点格不可回收。但「店员核销 / 代客时能不能把这张椅子分给刚到的客人」是<b>物理在座</b>问题，粒度到分钟、
     * 放座即空：不能沿用配额止界（否则 14:00-15:00 单坐到 14:30 放座后，配额 {@code actual_end_slot=15:00} 会把椅子
     * 一直锁到 15:00，店员分不出去 = 「限制太死」）。</p>
     *
     * <p><b>当下占用 = </b>{@code status='used'（已到店核销）AND actual_end_time IS NULL（未放座）AND slot_end > now
     * （计划未到点）}。命中 → 有人在坐，拒分；空 → 椅子当下空闲，店员可分（无论其早场被谁用过/放过）。
     * 放座（写 {@code actual_end_time}）或计划到点（{@code slot_end ≤ now}）即视为空出——分钟精度、不受整点配额影响。</p>
     *
     * <p><b>只看 {@code used}</b>：本模型（ADR-0016）座位仅在核销时绑定，{@code pending} 单 {@code seat_id=NULL}
     * 不占具体座；故「当下在座」仅 {@code used} 单。{@code cancelled/no_show/pay_closed/refunded} 全释放。
     * {@code FOR UPDATE} 锁住命中的在座行 + 配合 service 层 Redis 座位锁串行化并发分座（防两店员分同座 → 物理超卖）。</p>
     *
     * @param now 当前时分（{@link LocalTime}，分钟精度）
     * @return 该座当下在座的活跃 booking id 列表（非空即有人在坐 → SEAT_TAKEN 拒分）
     */
    @Select("SELECT id FROM gz_bean_booking " +
        "WHERE tenant_id = #{tenantId} AND store_id = #{storeId} AND seat_id = #{seatId} " +
        "  AND sess_date = #{sessDate} " +
        "  AND status = 'used' AND pay_status IN ('paying','paid') AND del_flag = '0' " +
        "  AND actual_end_time IS NULL AND slot_end > #{now} " +
        "FOR UPDATE")
    List<Long> selectSeatOccupiedNowForUpdate(@Param("tenantId") String tenantId,
                                              @Param("storeId") Long storeId,
                                              @Param("seatId") Long seatId,
                                              @Param("sessDate") LocalDate sessDate,
                                              @Param("now") LocalTime now);

    /**
     * 代客预约 × 排位共存（GZ-BEAN-047）：某具体座位 {@code seatId} 当日是否有「排位」(reserved) 单的区间
     * 与代客请求区间 {@code [reqStart, reqEnd)} 重叠。
     *
     * <p><b>排位单</b> = {@code status='pending' AND seat_id 非空 AND pay_status IN ('paying','paid')}
     * （preAssignSeat 提前挂座、未核销，激活看板 reserved 态）。pending 单 {@code actual_end_slot} 恒 NULL（未放座），
     * 占用止界即计划 {@code slot_end}，无 GZ-BEAN-045 放座整点误判问题。重叠判定
     * {@code slot_start < #{reqEnd} AND slot_end > #{reqStart}}。命中 → 代客时段会盖到排位客人的时段上，拒单
     * （{@link org.dromara.gz.bean.exception.GzBeanErrorCode#SEAT_RESERVED_OVERLAP}）；无命中（如排位 15:00-18:00、
     * 代客 13:00-15:00 端点相接不重叠）→ 放行，空档可代客。</p>
     *
     * <p><b>只查 pending（reserved）不查 used</b>：在座 used 单的区间重叠由 walkInCreate ③a
     * {@link #selectSeatUsedOverlapForUpdate} 判（GZ-BEAN-048）。故本查询专司「未来排位不被代客盖」。
     * {@code FOR UPDATE} 锁住命中的排位行串行化并发（同座并发代客 + 排位）。</p>
     *
     * @return 命中的排位 booking id 列表（非空即代客时段与排位重叠 → SEAT_RESERVED_OVERLAP 拒单）
     */
    @Select("SELECT id FROM gz_bean_booking " +
        "WHERE tenant_id = #{tenantId} AND store_id = #{storeId} AND seat_id = #{seatId} " +
        "  AND sess_date = #{sessDate} AND status = 'pending' AND pay_status IN ('paying','paid') AND del_flag = '0' " +
        "  AND slot_start < #{reqEnd} AND slot_end > #{reqStart} " +
        "FOR UPDATE")
    List<Long> selectReservedSeatOverlapForUpdate(@Param("tenantId") String tenantId,
                                                  @Param("storeId") Long storeId,
                                                  @Param("seatId") Long seatId,
                                                  @Param("sessDate") LocalDate sessDate,
                                                  @Param("reqStart") LocalTime reqStart,
                                                  @Param("reqEnd") LocalTime reqEnd);

    /**
     * 代客预约 × 在座单区间互斥（GZ-BEAN-048，占用座排后面空档）：某具体座位 {@code seatId} 当日<b>仍在占用的 used 单</b>
     * 的区间与代客请求区间 {@code [reqStart, reqEnd)} 是否重叠。
     *
     * <p><b>占用 used 单</b> = {@code status='used' AND actual_end_time IS NULL AND pay_status IN ('paying','paid')}
     * —— 已核销在店、未放座（放座 / 结单即 {@code actual_end_time} 非空、视为空闲不占，放座即空 GZ-BEAN-045）。占用止界用
     * 计划 {@code slot_end}（未放座单 {@code actual_end_slot} 恒 NULL），无整点误判。重叠判定
     * {@code slot_start < #{reqEnd} AND slot_end > #{reqStart}}（左闭右开，端点相接不算重叠）。</p>
     *
     * <p><b>取代 GZ-BEAN-046 的「当下物理在座」present-moment 判定</b>（{@link #selectSeatOccupiedNowForUpdate} 仍供
     * 核销分座 / 改派用，不动）：代客预约改成<b>按请求时段</b>判占用 —— 现占 13:00-15:00 的座，代客排其后空档
     * 15:00-17:00（不重叠）放行、盖到 14:00-16:00（重叠）拒。这样占用座也能代客排后面空档（客户 GZ-BEAN-048）。
     * {@code FOR UPDATE} 锁串行化并发。</p>
     *
     * @return 命中的在座 booking id 列表（非空即代客时段与在座单重叠 → SEAT_TAKEN 拒单）
     */
    @Select("SELECT id FROM gz_bean_booking " +
        "WHERE tenant_id = #{tenantId} AND store_id = #{storeId} AND seat_id = #{seatId} " +
        "  AND sess_date = #{sessDate} AND status = 'used' AND pay_status IN ('paying','paid') AND del_flag = '0' " +
        "  AND actual_end_time IS NULL " +
        "  AND slot_start < #{reqEnd} AND slot_end > #{reqStart} " +
        "FOR UPDATE")
    List<Long> selectSeatUsedOverlapForUpdate(@Param("tenantId") String tenantId,
                                              @Param("storeId") Long storeId,
                                              @Param("seatId") Long seatId,
                                              @Param("sessDate") LocalDate sessDate,
                                              @Param("reqStart") LocalTime reqStart,
                                              @Param("reqEnd") LocalTime reqEnd);

    /**
     * 当下代客（immediate）「座位此刻物理占用」判定（GZ-BEAN-048 blocker 修）：某具体座位当日是否有<b>任一未放座 used 单</b>。
     *
     * <p><b>与 {@link #selectSeatUsedOverlapForUpdate}（future 排后空档用，看计划区间重叠）和
     * {@link #selectSeatOccupiedNowForUpdate}（含 {@code slot_end > now}，会漏「超时赖座」）都不同</b>：当下代客的客人
     * <b>此刻就要坐下</b>，只要该座有一张 {@code status='used' AND actual_end_time IS NULL} 单（未点放座），就说明有人
     * <b>物理还在坐</b>（无论在座中还是<b>超时未结单赖座</b> —— overtime 单 {@code slot_end} 已过但客人没走、店员没放座，
     * {@code slot_end > now} 会漏判 → 同座物理撞人）。故本查询<b>不看 slot_end / 不看请求区间</b>，只认「未放座 = 有人」。
     * 命中 → 先放座 / 结单再代客（拒 {@code SEAT_TAKEN}）。已放座 / 结单单（{@code actual_end_time} 非空）= 客人已离场，不占。</p>
     *
     * <p>{@code FOR UPDATE} 锁串行化并发（配合 Redis 座位锁）。仅当下代客（immediate）用；排后空档（future→pending）不调
     * 本查询（未来单不要求座位此刻空）。</p>
     *
     * @return 该座当日未放座 used 单 id 列表（非空即此刻有人在坐/赖座 → 拒当下代客，先放座）
     */
    @Select("SELECT id FROM gz_bean_booking " +
        "WHERE tenant_id = #{tenantId} AND store_id = #{storeId} AND seat_id = #{seatId} " +
        "  AND sess_date = #{sessDate} AND status = 'used' AND pay_status IN ('paying','paid') AND del_flag = '0' " +
        "  AND actual_end_time IS NULL " +
        "FOR UPDATE")
    List<Long> selectSeatUnreleasedUsedForUpdate(@Param("tenantId") String tenantId,
                                                 @Param("storeId") Long storeId,
                                                 @Param("seatId") Long seatId,
                                                 @Param("sessDate") LocalDate sessDate);

    /**
     * 分座候选列表用「当下物理占用」批量版（GZ-BEAN-043，无锁）：某门店某日此刻仍有人在坐的具体座位 id 去重列表。
     * 判定口径同 {@link #selectSeatOccupiedNowForUpdate}（{@code used + 未放座 + slot_end > now}），
     * service 层据此从同桌型启用座中排除，给店员一份「当下真能分」的候选。
     *
     * @return 当下在座的座位 id 去重列表
     */
    @Select("SELECT DISTINCT seat_id FROM gz_bean_booking " +
        "WHERE tenant_id = #{tenantId} AND store_id = #{storeId} AND seat_id IS NOT NULL " +
        "  AND sess_date = #{sessDate} " +
        "  AND status = 'used' AND pay_status IN ('paying','paid') AND del_flag = '0' " +
        "  AND actual_end_time IS NULL AND slot_end > #{now}")
    List<Long> selectSeatOccupiedNowIds(@Param("tenantId") String tenantId,
                                        @Param("storeId") Long storeId,
                                        @Param("sessDate") LocalDate sessDate,
                                        @Param("now") LocalTime now);


    // ============================================================
    //  GZ-BEAN-025 前 N 名免费促销周期桶计数（ADR-0015 §4 / doc/11 §3.11）
    // ============================================================

    /**
     * 周期桶内已发免费单数（GZ-BEAN-025，ADR-0015 §4）。
     *
     * <p>统计某门店在当前周期桶时间范围 {@code [bucketStart, bucketEnd)}（按 {@code create_time} 切桶）内
     * 已发放的免费单数（{@code is_free=1}），与 {@code gz_bean_free_promo.free_count} 比对判名额是否用尽。</p>
     *
     * <p><b>名额不回收（防刷，doc/11 §3.11）</b>：计数口径 = {@code is_free=1 AND del_flag='0'}，
     * <b>含 cancelled / no_show</b>（不加 status 过滤）—— 已发即占名额，防「下单占免费 → 取消 → 再刷」。</p>
     *
     * <p>下单事务内调用前由 service 抢 Redis 桶锁 {@code gz:bean:lock:free_promo:{store}:{bucket}} 串行化；
     * 本计数本身无 {@code FOR UPDATE}（桶锁已串行化发放，且 is_free 单刚 INSERT 即在同事务可见，
     * 串行下读到的计数准确）。status 接口（无锁展示用）也复用本查询。</p>
     *
     * <p><b>tenant_id 显式传</b>：mp 下单事务 / 匿名 status 接口 JWT 可能无可靠 tenant，由 service 显式传入
     * （同 submit / seat-overlap 注释）。</p>
     *
     * @param tenantId    租户
     * @param storeId     门店
     * @param bucketStart 当前周期桶起（含），create_time &gt;= 此值
     * @param bucketEnd   当前周期桶止（不含），create_time &lt; 此值
     * @return 桶内已发免费单数（含已取消 / 未到店的免费单）
     */
    @Select("SELECT COUNT(*) FROM gz_bean_booking " +
        "WHERE tenant_id = #{tenantId} AND store_id = #{storeId} AND is_free = 1 " +
        "  AND create_time >= #{bucketStart} AND create_time < #{bucketEnd} AND del_flag = '0'")
    long countBucketIssuedFree(@Param("tenantId") String tenantId,
                               @Param("storeId") Long storeId,
                               @Param("bucketStart") LocalDateTime bucketStart,
                               @Param("bucketEnd") LocalDateTime bucketEnd);

    // ============================================================
    //  GZ-BEAN-026 店内计时看板（ADR-0015 §5 / doc/11 §3.12 / doc/10 §11 看板子流程）
    // ============================================================

    /**
     * 看板：拉某门店某日全部<b>活跃且挂具体座位</b>的 booking（GZ-BEAN-026）。
     *
     * <p>{@code seat_id IS NOT NULL}（影院选座单才进看板，legacy 无具体座位的旧单不进）+
     * 活跃口径 {@code status IN ('pending','used') AND pay_status IN ('paying','paid')}
     * （ADR-0007 沿用，与防超卖一致：pending 待到店 / used 在店使用中均占座）。service 层按 seat_id
     * 归集，对每座算看板状态（空闲/已约未到/使用中/临近结束/已超时）。</p>
     *
     * <p>按 seat_id、slot_start 升序，便于 service 同座多单时取「覆盖当前时刻 / 最早未结束」的当前单。
     * tenant_id 显式传（看板由 admin 登录态 / mp 店员态调，统一显式 scope 同 seat-map 注释）。</p>
     *
     * @param tenantId 租户
     * @param storeId  门店
     * @param sessDate 看板日期
     * @return 当日该店全部活跃挂座单（含 actual_end_time/slot，service 据此算看板状态 + 放座/超时）
     */
    @Select("SELECT * FROM gz_bean_booking " +
        "WHERE tenant_id = #{tenantId} AND store_id = #{storeId} AND sess_date = #{sessDate} " +
        "  AND seat_id IS NOT NULL " +
        "  AND status IN ('pending','used') AND pay_status IN ('paying','paid') AND del_flag = '0' " +
        "ORDER BY seat_id, slot_start")
    List<GzBeanBooking> selectActiveBookingsForBoard(@Param("tenantId") String tenantId,
                                                     @Param("storeId") Long storeId,
                                                     @Param("sessDate") LocalDate sessDate);

    /**
     * 延时撞占校验（GZ-BEAN-026 E4b，ADR-0015 §5）：判某座在「新增格区间」{@code [reqStart, reqEnd)}
     * 是否与<b>除自身外</b>的活跃单重叠。{@code FOR UPDATE} 锁住该座活跃单串行化延时与并发下单。
     *
     * <p>具体座位区间互斥 + 占用止界 {@code COALESCE(actual_end_slot, slot_end)}，多
     * {@code id != #{excludeId}} 排除被延时单本身（否则它自己的占用区间会命中）。命中任一行 →
     * 新增格已被别人占，拒绝延时（E4b）。</p>
     *
     * @return 命中的活跃 booking id 列表（非空即新增格被占 → 拒绝延时）
     */
    @Select("SELECT id FROM gz_bean_booking " +
        "WHERE tenant_id = #{tenantId} AND store_id = #{storeId} AND seat_id = #{seatId} " +
        "  AND sess_date = #{sessDate} AND id != #{excludeId} " +
        "  AND slot_start < #{reqEnd} AND COALESCE(actual_end_slot, slot_end) > #{reqStart} " +
        "  AND status IN ('pending','used') AND pay_status IN ('paying','paid') AND del_flag = '0' " +
        "FOR UPDATE")
    List<Long> selectActiveSeatOverlapExcludingForUpdate(@Param("tenantId") String tenantId,
                                                         @Param("storeId") Long storeId,
                                                         @Param("seatId") Long seatId,
                                                         @Param("sessDate") LocalDate sessDate,
                                                         @Param("excludeId") Long excludeId,
                                                         @Param("reqStart") LocalTime reqStart,
                                                         @Param("reqEnd") LocalTime reqEnd);

    /**
     * 提前放座条件 UPDATE（GZ-BEAN-026，doc/11 §3.12 / doc/10 §11）：写 {@code actual_end_time} +
     * {@code actual_end_slot}，<b>不改 status</b>（仍 {@code used}，是已用记录）/ 不改 pay_status。
     *
     * <p>WHERE 守卫 {@code status='used' AND actual_end_time IS NULL} → 仅在店使用中（已核销）且未放过座的单
     * 可放座，幂等：已放过座 / 非 used 的单 affected=0。放座后该座 {@code actual_end_slot} 之后的格立即可被再约
     * （防超卖区间重叠判断收紧到 actual_end_slot，ADR-0015 §2）。</p>
     *
     * @return 受影响行数（1 = 放座成功 / 0 = 已放座或非 used，幂等跳过）
     */
    @Update("UPDATE gz_bean_booking " +
        "SET actual_end_time = #{actualEndTime}, actual_end_slot = #{actualEndSlot} " +
        "WHERE id = #{id} AND status = 'used' AND actual_end_time IS NULL AND del_flag = '0'")
    int markSeatReleased(@Param("id") Long id,
                         @Param("actualEndTime") LocalDateTime actualEndTime,
                         @Param("actualEndSlot") LocalTime actualEndSlot);

    /**
     * 延时条件 UPDATE（GZ-BEAN-026，doc/11 §3.12 / doc/10 §11）：把 {@code slot_end} 推到 {@code newSlotEnd}。
     *
     * <p>WHERE 守卫 {@code status='used' AND slot_end = #{oldSlotEnd}} → 仅在店使用中（已核销）单可延时，
     * 且 slot_end 未被并发改过（乐观防丢更新）。延时前由 service 按具体座位区间互斥校验新增格未被别人占
     * （占了拒绝 E4b，ADR-0015 §5）。V1 延时不走线上补付。</p>
     *
     * @return 受影响行数（1 = 延时成功 / 0 = 非 used 或 slot_end 已变，跳过）
     */
    @Update("UPDATE gz_bean_booking " +
        "SET slot_end = #{newSlotEnd} " +
        "WHERE id = #{id} AND status = 'used' AND slot_end = #{oldSlotEnd} AND del_flag = '0'")
    int extendSlotEnd(@Param("id") Long id,
                      @Param("oldSlotEnd") LocalTime oldSlotEnd,
                      @Param("newSlotEnd") LocalTime newSlotEnd);

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

    // ============================================================
    //  GZ-BEAN-041 看板过期单批量结单 / 补核销（kevin-test §6）
    // ============================================================

    /**
     * 过期单补核销条件 UPDATE（GZ-BEAN-041 / kevin-test §6）：{@code status: pending|no_show → used}
     * （no_show 翻案）+ 写 verify_time / verified_by + dedup_token 切 booking_no。<b>不绑 seat_id</b>
     * （历史结算，不上看板座位、不撞后续预约）。
     *
     * <p>WHERE 守卫 {@code status IN ('pending','no_show')} → 天然幂等：已 used/cancelled 的单 affected=0。</p>
     *
     * @return 受影响行数（1 = 补核销成功 / 0 = 已非 pending|no_show，幂等跳过）
     */
    @Update("UPDATE gz_bean_booking " +
        "SET status = 'used', verify_time = #{verifyTime}, verified_by = #{verifiedBy}, dedup_token = booking_no " +
        "WHERE id = #{id} AND status IN ('pending','no_show') AND del_flag = '0'")
    int settleAsCompleted(@Param("id") Long id,
                          @Param("verifyTime") LocalDateTime verifyTime,
                          @Param("verifiedBy") String verifiedBy);

    // ============================================================
    //  拼豆营业额（周/月/季度/日整合 + 桌型×计费方式拆分；数据源 gz_bean_booking 非支付流水 —— 现金代客单不落 gz_pay_transaction）
    // ============================================================

    /**
     * 拼豆营业额区间汇总（数据源 = gz_bean_booking，口径「只统计拼豆」）。
     *
     * <p><b>为何用 booking 表不用 gz_pay_transaction</b>：现金代客单（店员看板 walk-in / admin-create）线下收款、
     * 不走微信支付、不落 {@code gz_pay_transaction}；只查支付流水会漏掉现金单，营业额偏低。故直接对 booking 明细
     * 聚合，与门店实际收款一致。</p>
     *
     * <p><b>计入口径</b>：{@code pay_status='paid'}（已付成功，含线下现金代客）{@code AND is_free=0}（前 N 名免费促销单
     * amount_cent=0、不计营业额）{@code AND del_flag='0'}。按 {@code sess_date}（服务日）区间过滤 —— 现金单当天即服务，
     * 口径自然；与对账中心 4% 分成口径（按 paid_time）有意分开。</p>
     *
     * <p><b>现金 vs 线上拆分</b>：{@code out_trade_no} 非空 = 走微信支付（线上）；为空 = 线下现金收款（代客单）。
     * 直接反映收款方式，比 {@code source} 列更准（source 只区分下单来源，不区分是否真走微信）。</p>
     *
     * <p><b>tenant_id 显式传</b>：与本 mapper 其它直查一致，由 service 从登录态取 tenant 显式传入，不依赖拦截器。
     * 返回单行聚合结果（无命中时 count/sum 为 0）。</p>
     *
     * @param tenantId 租户
     * @param storeId  门店（null = 全部门店，owner 视角）
     * @param start    区间起（含）
     * @param end      区间止（含）
     * @return 单行汇总（totalCent / orderCount / cashCent / cashCount / onlineCent / onlineCount）
     */
    @Select("<script>" +
        "SELECT " +
        "  COALESCE(SUM(amount_cent), 0) AS totalCent, " +
        "  COUNT(*) AS orderCount, " +
        "  COALESCE(SUM(CASE WHEN out_trade_no IS NULL THEN amount_cent ELSE 0 END), 0) AS cashCent, " +
        "  COALESCE(SUM(CASE WHEN out_trade_no IS NULL THEN 1 ELSE 0 END), 0) AS cashCount, " +
        "  COALESCE(SUM(CASE WHEN out_trade_no IS NOT NULL THEN amount_cent ELSE 0 END), 0) AS onlineCent, " +
        "  COALESCE(SUM(CASE WHEN out_trade_no IS NOT NULL THEN 1 ELSE 0 END), 0) AS onlineCount " +
        "FROM gz_bean_booking " +
        "WHERE tenant_id = #{tenantId} AND sess_date BETWEEN #{start} AND #{end} " +
        "  AND pay_status = 'paid' AND is_free = 0 AND del_flag = '0' " +
        "<if test='storeId != null'> AND store_id = #{storeId} </if>" +
        "</script>")
    org.dromara.gz.bean.domain.vo.GzBeanRevenueAggregateVO.Summary sumRangeRevenue(@Param("tenantId") String tenantId,
                                                                                    @Param("storeId") Long storeId,
                                                                                    @Param("start") LocalDate start,
                                                                                    @Param("end") LocalDate end);

    /**
     * 拼豆营业额「时间桶 × 类目」逐行聚合（口径同 {@link #sumRangeRevenue}）。
     *
     * <p><b>时间桶</b>由 {@code granularity} 决定（周一起 ISO 周 / 自然月 / 自然季度 / 自然日）；
     * <b>类目</b> = {@code seat_type}（COALESCE 兜底 unknown）× {@code is_day_pass}（COALESCE 兜底 0=计时）。
     * {@code typeName} 取 {@code MAX(seat_type_snapshot)} 供前端给自定义桌型标注中文名（single/double/quad
     * 前端走 i18n 覆盖，不依赖此值）。MySQL 允许 GROUP BY 别名，故桶表达式只在 SELECT 出现一次。</p>
     *
     * @param tenantId    租户
     * @param storeId     门店（null = 全部门店）
     * @param start       区间起（含）
     * @param end         区间止（含）
     * @param granularity 时间粒度 day/week/month/quarter（service 已白名单校验）
     * @return 逐行（periodKey / seatType / isDayPass / totalCent / orderCount / typeName），service 透视成矩形
     */
    @Select("<script>" +
        "SELECT " +
        "  <choose>" +
        "    <when test='granularity == \"week\"'>DATE_FORMAT(sess_date, '%x-W%v')</when>" +
        "    <when test='granularity == \"month\"'>DATE_FORMAT(sess_date, '%Y-%m')</when>" +
        "    <when test='granularity == \"quarter\"'>CONCAT(YEAR(sess_date), '-Q', QUARTER(sess_date))</when>" +
        "    <otherwise>DATE_FORMAT(sess_date, '%Y-%m-%d')</otherwise>" +
        "  </choose> AS periodKey, " +
        "  COALESCE(seat_type, 'unknown') AS seatType, " +
        "  COALESCE(is_day_pass, 0) AS isDayPass, " +
        "  COALESCE(SUM(amount_cent), 0) AS totalCent, " +
        "  COUNT(*) AS orderCount, " +
        "  MAX(seat_type_snapshot) AS typeName " +
        "FROM gz_bean_booking " +
        "WHERE tenant_id = #{tenantId} AND sess_date BETWEEN #{start} AND #{end} " +
        "  AND pay_status = 'paid' AND is_free = 0 AND del_flag = '0' " +
        "<if test='storeId != null'> AND store_id = #{storeId} </if>" +
        "GROUP BY periodKey, seatType, isDayPass " +
        "ORDER BY periodKey, seatType, isDayPass" +
        "</script>")
    List<org.dromara.gz.bean.domain.vo.GzBeanRevenueAggregateVO.PeriodCategoryRow> sumRangeByPeriodCategory(
        @Param("tenantId") String tenantId,
        @Param("storeId") Long storeId,
        @Param("start") LocalDate start,
        @Param("end") LocalDate end,
        @Param("granularity") String granularity);
}
