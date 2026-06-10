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
        "    verified_by = #{verifiedBy}, verify_time = #{verifyTime}, version = version + 1 " +
        "WHERE id = #{id} AND version = #{version} AND status = 'submitted' AND del_flag = '0'")
    int markConfirmedOnsite(@Param("id") Long id,
                            @Param("version") Integer version,
                            @Param("verifyImageIds") String verifyImageIds,
                            @Param("finalAmountCent") Long finalAmountCent,
                            @Param("verifiedBy") String verifiedBy,
                            @Param("verifyTime") LocalDateTime verifyTime);

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
     * <p>WHERE 守卫 {@code status='paying'} → 幂等（已非 paying 则 affected=0 跳过）。</p>
     *
     * @param id 预约单 id
     * @return 受影响行数（1 = 推进成功 / 0 = 幂等跳过）
     */
    @Update("UPDATE gz_recycle_appointment " +
        "SET status = 'paid', version = version + 1 " +
        "WHERE id = #{id} AND status = 'paying' AND del_flag = '0'")
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
     * 扫 paying 态预约单 id（RECYCLE-003 paid 回写钩子，syncPayoutResult 调）。
     *
     * @param limit 单轮上限（防雪崩）
     * @return paying 态预约单 id 列表
     */
    @Select("SELECT id FROM gz_recycle_appointment " +
        "WHERE status = 'paying' AND del_flag = '0' ORDER BY id LIMIT #{limit}")
    List<Long> selectPayingIds(@Param("limit") int limit);

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
