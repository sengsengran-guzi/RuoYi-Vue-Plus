package org.dromara.gz.recycle.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.gz.recycle.domain.entity.GzRecycleAppointment;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * gz_recycle_appointment 数据层（GZ-RECYCLE-002/003，doc/11 §12.2）。
 *
 * <p>多租户由 ruoyi {@code TenantLineInnerInterceptor} 自动 append {@code WHERE tenant_id = ?}；
 * 软删由 {@code @TableLogic} 自动过滤；分页由 {@code PaginationInnerInterceptor} 注入。</p>
 *
 * <p><b>RECYCLE-003 状态推进（手写原子 UPDATE + version 乐观锁守卫）</b>：状态机每步 WHERE 含
 * {@code status=<前态> AND version=#{version}} → affected=1 推进 / affected=0 并发或已推进（幂等跳过），
 * 防店员重复点「确认/触发打款」并发冲突（doc/11 §12.2 触发打款幂等）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-RECYCLE-002/003)
 */
public interface GzRecycleAppointmentMapper extends BaseMapperPlus<GzRecycleAppointment, GzRecycleAppointment> {

    /**
     * 取当日已生成的最大日内序号（appointment_no 生成用，doc/11 §12.2 RCY-yyyyMMdd-6位序号）。
     *
     * <p>与 GZ-PAY out_payout_no 同款 DB MAX+1 策略；并发由 appointment_no UNIQUE(tenant_id, appointment_no)
     * 兜底（撞唯一约束时 service 重试 / 失败回滚）。SUBSTRING 截前缀后的纯序号段 CAST UNSIGNED 取最大。</p>
     *
     * @param prefixDate 形如 "RCY-20260622-"（前缀 + 日期 + 连字符）
     * @return 当日最大序号（无则 0）
     */
    @Select("SELECT COALESCE(MAX(CAST(SUBSTRING(appointment_no, LENGTH(#{prefixDate}) + 1) AS UNSIGNED)), 0) " +
        "FROM gz_recycle_appointment WHERE appointment_no LIKE CONCAT(#{prefixDate}, '%')")
    long selectMaxDailySeq(@Param("prefixDate") String prefixDate);

    /**
     * 按门店 id 查门店名（详情 VO 顾客可见门店名，契约 15a §E.1）。
     *
     * <p>跨表轻量查 {@code gz_bean_store.name}（recycle 模块不依赖 gz-bean 实体 → 走原生 SQL 仅取名字段，
     * 避免引入跨模块依赖）。tenant_id 由 {@code TenantLineInnerInterceptor} 自动 append；门店不存在返 null。</p>
     *
     * @param storeId 门店 id
     * @return 门店名（无则 null）
     */
    @Select("SELECT name FROM gz_bean_store WHERE id = #{storeId} AND del_flag = '0' LIMIT 1")
    String selectStoreNameById(@Param("storeId") Long storeId);

    /**
     * 某门店某日某到店时段的活跃占用数（GZ-RECYCLE-007 时段容量防超卖，FOR UPDATE）。
     *
     * <p>「占用该档」= 活跃单 {@code time_slot_id = slotId}（本单选此档）<b>或</b> {@code spill_time_slot_id = slotId}
     * （某大单溢出占了此档）。活跃态 = {@code submitted / confirmed_onsite / paying / paid / payout_failed}
     * （{@code cancelled / no_show} 释放不计）。每档容量 = 1，故调用方 {@code >0 即已占}。</p>
     *
     * <p><b>防超卖正确性前提</b>（镜像拼豆 {@code countActiveCoveringSlotForUpdate}）：{@code FOR UPDATE} 靠 InnoDB
     * 间隙锁串行化并发同档下单（命中 0 行也锁索引区段挡并发 INSERT 后读旧 count），<b>仅 REPEATABLE_READ 成立</b>，
     * service 上 {@code @Transactional(isolation = REPEATABLE_READ)} + {@code (store,date)} Redis 锁双保险。</p>
     *
     * <p><b>tenant_id 显式传</b>：mp 下单事务用户态 JWT 无 tenant，不依赖 ruoyi 拦截器自动注入（同拼豆 submit），
     * 由 service 从 user 取 tenant 显式传入。</p>
     *
     * @param tenantId 租户 id（显式传）
     * @param storeId  门店 id
     * @param apptDate 到店日期
     * @param slotId   待判定占用的到店时段 id
     * @return 该档当前活跃占用数（≥ 1 即已被占）
     */
    @Select("SELECT COUNT(*) FROM gz_recycle_appointment " +
        "WHERE tenant_id = #{tenantId} AND store_id = #{storeId} AND appt_date = #{apptDate} " +
        "  AND (time_slot_id = #{slotId} OR spill_time_slot_id = #{slotId}) " +
        "  AND status IN ('submitted', 'confirmed_onsite', 'paying', 'paid', 'payout_failed') AND del_flag = '0' " +
        "FOR UPDATE")
    long countActiveHoldingSlotForUpdate(@Param("tenantId") String tenantId,
                                         @Param("storeId") Long storeId,
                                         @Param("apptDate") LocalDate apptDate,
                                         @Param("slotId") Long slotId);

    /**
     * 同一用户当前进行中的回收预约数（客户 7.24「一人一单」守卫，FOR UPDATE）。
     *
     * <p>「进行中」= {@code submitted / confirmed_onsite / paying / payout_failed}
     * （{@code paid 已到账 / cancelled 已取消 / no_show 已过期} 三终态释放，可再预约）。
     * <b>注意与 {@link #countActiveHoldingSlotForUpdate} 活跃集不同</b>：那个含 {@code paid}（当天仍占时段档），
     * 本守卫排除 {@code paid}（拿到钱即结清，可再约）。</p>
     *
     * <p>并发：service 上层先抢 {@code gz:recycle:lock:user_submit:{userId}} Redis 锁串行化同用户提交（防连点两单都过），
     * {@code FOR UPDATE} + REPEATABLE_READ 间隙锁做 DB 层双保险。tenant_id 显式传（mp JWT 无 tenant，同 submit 口径）。</p>
     *
     * <p>⚠️ 状态集须与 {@code GzRecycleAppointmentServiceImpl.USER_ACTIVE_STATUSES} 保持一致（MyBatis @Select
     * 无法引用 Java 常量，故此处内联字面量）；改「进行中」口径需两处同步，否则守卫（拦下单）与 /active 预检口径分叉。</p>
     *
     * @param tenantId 租户 id（显式传）
     * @param userId   提交用户 id
     * @return 该用户当前进行中的回收预约数（≥ 1 即不可再约）
     */
    @Select("SELECT COUNT(*) FROM gz_recycle_appointment " +
        "WHERE tenant_id = #{tenantId} AND user_id = #{userId} " +
        "  AND status IN ('submitted', 'confirmed_onsite', 'paying', 'payout_failed') AND del_flag = '0' " +
        "FOR UPDATE")
    long countActiveByUserForUpdate(@Param("tenantId") String tenantId, @Param("userId") Long userId);

    /**
     * 店员核对确认：submitted → confirmed_onsite（GZ-RECYCLE-003 AC1，doc/10 §13.N8）。
     *
     * <p>原子写入核对留痕（verify_image_ids / final_amount_cent / verified_by / verify_time）+ 推进状态 + version+1。
     * WHERE 守卫 {@code status='submitted' AND version=#{version}} → affected=1 成功 / 0 已被并发推进（幂等）。</p>
     *
     * @param id              预约单 id
     * @param version         期望版本号
     * @param verifyImageIds  核对照逗号分隔 image_id（FK gz_file_object，usage_type=recycle_verify_image）
     * @param finalAmountCent 核对后最终金额（分）
     * @param verifiedBy      核对店员 admin 用户名
     * @param verifyTime      核对时间
     * @return 受影响行数（1 = 推进成功 / 0 = 并发或非 submitted，幂等跳过）
     */
    @Update("UPDATE gz_recycle_appointment " +
        "SET status = 'confirmed_onsite', verify_image_ids = #{verifyImageIds}, final_amount_cent = #{finalAmountCent}, " +
        "    verified_by = #{verifiedBy}, verify_time = #{verifyTime}, verify_remark = #{verifyRemark}, version = version + 1 " +
        "WHERE id = #{id} AND version = #{version} AND status = 'submitted' AND del_flag = '0'")
    int markConfirmedOnsite(@Param("id") Long id,
                            @Param("version") Integer version,
                            @Param("verifyImageIds") String verifyImageIds,
                            @Param("finalAmountCent") Long finalAmountCent,
                            @Param("verifiedBy") String verifiedBy,
                            @Param("verifyTime") LocalDateTime verifyTime,
                            @Param("verifyRemark") String verifyRemark);

    /**
     * 触发打款回填：confirmed_onsite → paying（GZ-RECYCLE-003 AC2，doc/10 §13.N9）。
     *
     * <p>回填关联打款单号 out_payout_no + 推进 paying。WHERE 守卫 {@code status='confirmed_onsite' AND version=#{version}}
     * → 防重复触发打款（version 乐观锁 + 1:1 幂等双保险，§4.8 payout 侧亦有 business_order_no 查重）。</p>
     *
     * @param id          预约单 id
     * @param version     期望版本号
     * @param outPayoutNo 关联打款单号
     * @return 受影响行数（1 = 推进成功 / 0 = 并发或非 confirmed_onsite，幂等跳过）
     */
    @Update("UPDATE gz_recycle_appointment " +
        "SET status = 'paying', out_payout_no = #{outPayoutNo}, version = version + 1 " +
        "WHERE id = #{id} AND version = #{version} AND status = 'confirmed_onsite' AND del_flag = '0'")
    int markPaying(@Param("id") Long id, @Param("version") Integer version, @Param("outPayoutNo") String outPayoutNo);

    /**
     * 打款到账回写：paying → paid（GZ-RECYCLE-003 AC2，doc/10 §13.N10；查单 success 钩子调）。
     *
     * <p>WHERE 守卫 {@code status IN ('paying','payout_failed')} → 幂等（已 paid/其它终态则 affected=0 跳过）。</p>
     *
     * <p><b>D16 B4 收敛</b>：纳入 {@code payout_failed} —— owner 若从 admin『打款单管理』页重试
     * （{@code GzPayPayoutServiceImpl.retryPayout} 只推进 payout 单、不回写回收单，回收单仍停 payout_failed），
     * payout 查单收敛 success 后本回写仍需把 payout_failed→paid，避免「钱已二次转出但回收单永久卡 payout_failed」
     * （资金/单据脱钩）。</p>
     *
     * @param id 预约单 id
     * @return 受影响行数（1 = 推进成功 / 0 = 幂等跳过）
     */
    @Update("UPDATE gz_recycle_appointment " +
        "SET status = 'paid', version = version + 1 " +
        "WHERE id = #{id} AND status IN ('paying', 'payout_failed') AND del_flag = '0'")
    int markPaid(@Param("id") Long id);

    /**
     * 打款失败回写：paying → payout_failed（GZ-RECYCLE-003 AC2/AC3，doc/10 §13.E4；查单 failed 钩子调，留人工重试）。
     *
     * <p>WHERE 守卫 {@code status='paying'} → 幂等。</p>
     *
     * @param id 预约单 id
     * @return 受影响行数（1 = 推进成功 / 0 = 幂等跳过）
     */
    @Update("UPDATE gz_recycle_appointment " +
        "SET status = 'payout_failed', version = version + 1 " +
        "WHERE id = #{id} AND status = 'paying' AND del_flag = '0'")
    int markPayoutFailed(@Param("id") Long id);

    /**
     * 失败重试回到打款中：payout_failed → paying（GZ-RECYCLE-003 AC3，owner 重试）。
     *
     * <p>WHERE 守卫 {@code status='payout_failed'} → 幂等。重试由 owner 在 admin 触发，PAY-105 侧
     * retryPayout 重置 payout 单 failed→created→受理，本回写把预约单拉回 paying 等查单收敛。</p>
     *
     * @param id 预约单 id
     * @return 受影响行数
     */
    @Update("UPDATE gz_recycle_appointment " +
        "SET status = 'paying', version = version + 1 " +
        "WHERE id = #{id} AND status = 'payout_failed' AND del_flag = '0'")
    int markRetryPaying(@Param("id") Long id);

    /**
     * 凌晨任务 no_show 兜底：submitted → no_show（GZ-RECYCLE-003 AC7，doc/10 §13.N12 / F12.3）。
     *
     * <p>店员标记优先；本 UPDATE 由 SnailJob 凌晨扫超 appt_date 仍 submitted 单触发。WHERE 守卫
     * {@code status='submitted'} → 幂等（已到店确认的不误标）。</p>
     *
     * @param id 预约单 id
     * @return 受影响行数（1 = 标记成功 / 0 = 已非 submitted，幂等跳过）
     */
    @Update("UPDATE gz_recycle_appointment " +
        "SET status = 'no_show', version = version + 1 " +
        "WHERE id = #{id} AND status = 'submitted' AND del_flag = '0'")
    int markNoShow(@Param("id") Long id);

    /**
     * 扫待 payout 回写收敛的预约单 id（RECYCLE-003 paid 回写钩子，syncPayoutResult 调）。
     *
     * <p><b>D16 B4 收敛</b>：除 {@code paying} 外，纳入 {@code payout_failed} 且 {@code out_payout_no} 非空的单 ——
     * owner 从 admin『打款单管理』页重试（不回写回收单状态）后，payout 单可经查单收敛 success，
     * 本扫描须把这些单也喂给 {@code syncOne} → {@code markPaid}，否则回收单永久卡 payout_failed（资金/单据脱钩）。
     * payout 仍 failed 的单 syncOne 走 failed 分支幂等 no-op，不会误推进。</p>
     *
     * @param limit 单轮上限（防雪崩）
     * @return 待收敛预约单 id 列表（paying / payout_failed 且有 out_payout_no）
     */
    @Select("SELECT id FROM gz_recycle_appointment " +
        "WHERE status IN ('paying', 'payout_failed') AND out_payout_no IS NOT NULL AND del_flag = '0' " +
        "ORDER BY id LIMIT #{limit}")
    List<Long> selectSyncablePayoutIds(@Param("limit") int limit);

    /**
     * 扫超预约日仍 submitted 的单 id（no_show 凌晨任务，AC7）。
     *
     * <p>条件：{@code status='submitted' AND appt_date < #{today}}（预约日已过仍未到店核对）。</p>
     *
     * @param today 今日（北京时间），扫 appt_date 严格早于今日的 submitted 单
     * @param limit 单轮上限
     * @return 待标 no_show 的单 id 列表
     */
    @Select("SELECT id FROM gz_recycle_appointment " +
        "WHERE status = 'submitted' AND appt_date < #{today} AND del_flag = '0' ORDER BY id LIMIT #{limit}")
    List<Long> selectExpiredSubmittedIds(@Param("today") LocalDate today, @Param("limit") int limit);
}
