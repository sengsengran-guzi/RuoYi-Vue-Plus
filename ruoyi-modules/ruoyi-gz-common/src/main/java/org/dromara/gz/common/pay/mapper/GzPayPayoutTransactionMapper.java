package org.dromara.gz.common.pay.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.gz.common.pay.domain.entity.GzPayPayoutTransaction;
import org.dromara.gz.common.pay.domain.vo.GzPayPayoutTransactionVO;

import java.time.LocalDateTime;
import java.util.List;

/**
 * gz_pay_payout_transaction 数据层（GZ-PAY-105，doc/11 §4.8）。
 *
 * <p>多租户 / 软删由 ruoyi 拦截器自动处理。除 BaseMapperPlus CRUD 外，暴露手写方法承载状态机原子推进
 * （created → processing → success/failed）+ 双重幂等（out_payout_no / batch_id UNIQUE + 1:1 业务单查重）
 * + SnailJob 查单扫描（ADR-0006 §3/§5）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-PAY-105)
 */
public interface GzPayPayoutTransactionMapper extends BaseMapperPlus<GzPayPayoutTransaction, GzPayPayoutTransactionVO> {

    /**
     * 按 out_payout_no 查打款单（幂等 SELECT / 查单推进用，至多 1 条）。
     *
     * @param outPayoutNo 业务出账单号
     * @return 命中单（无则 null）
     */
    @Select("SELECT * FROM gz_pay_payout_transaction WHERE out_payout_no = #{outPayoutNo} AND del_flag = '0' LIMIT 1")
    GzPayPayoutTransaction selectByOutPayoutNo(@Param("outPayoutNo") String outPayoutNo);

    /**
     * 1:1 幂等查重（ADR-0006 §5）：按 business_order_no 统计「非 failed/cancelled」的活跃打款单数。
     *
     * <p>建单前调用 —— &gt; 0 表示该回收预约已有 created/processing/success 单在途或已成，拒绝重复发起
     * （防店员重复点「打款」）。failed/cancelled 不计入（允许失败后重试新建单）。</p>
     *
     * @param businessOrderNo 回收预约号
     * @return 活跃（created/processing/success）打款单数
     */
    @Select("SELECT COUNT(*) FROM gz_pay_payout_transaction " +
        "WHERE business_order_no = #{businessOrderNo} AND status NOT IN ('failed','cancelled') AND del_flag = '0'")
    long countActiveByBusinessOrderNo(@Param("businessOrderNo") String businessOrderNo);

    /**
     * 1:1 幂等查重（ADR-0006 §5）：返回该回收预约「非 failed/cancelled」的活跃打款单（至多 1 条）。
     *
     * <p>建单前调用 —— 命中则幂等返回已有单（不重复发起）。failed/cancelled 不返回（允许失败后重试新建）。
     * 正常态下活跃单至多 1 条（1:1 约束 + out_payout_no/business_order_no UNIQUE 兜底）。</p>
     *
     * @param businessOrderNo 回收预约号
     * @return 活跃打款单（无则 null）
     */
    @Select("SELECT * FROM gz_pay_payout_transaction " +
        "WHERE business_order_no = #{businessOrderNo} AND status NOT IN ('failed','cancelled') AND del_flag = '0' " +
        "ORDER BY id DESC LIMIT 1")
    GzPayPayoutTransaction selectByActiveBusinessOrderNo(@Param("businessOrderNo") String businessOrderNo);

    /**
     * 取当日已生成的最大日内序号（out_payout_no 生成用，doc/11 §4.8 PAYOUT-yyyyMMdd-6位序号）。
     *
     * <p>与 GZ-PAY out_trade_no 同款 DB MAX+1 策略；并发由 out_payout_no UNIQUE 兜底重试。</p>
     *
     * @param prefixDate 形如 "PAYOUT-20260621-"（前缀 + 日期 + 连字符）
     * @return 当日最大序号（无则 0）
     */
    @Select("SELECT COALESCE(MAX(CAST(SUBSTRING(out_payout_no, LENGTH(#{prefixDate}) + 1) AS UNSIGNED)), 0) " +
        "FROM gz_pay_payout_transaction WHERE out_payout_no LIKE CONCAT(#{prefixDate}, '%')")
    long selectMaxDailySeq(@Param("prefixDate") String prefixDate);

    /**
     * 扫 processing 态打款单 id（SnailJob 查单，ADR-0006 §3 主动查单优先）。
     *
     * <p>条件：{@code status='processing'} + LIMIT（防雪崩，单轮控量）。多租户由 service
     * {@code TenantHelper.ignore} 全租户扫（cron 无登录态，与 PAY-102 同思路）。</p>
     *
     * @param limit 单轮上限
     * @return 待查单的 processing 单 id 列表
     */
    @Select("SELECT id FROM gz_pay_payout_transaction " +
        "WHERE status = 'processing' AND del_flag = '0' ORDER BY id LIMIT #{limit}")
    List<Long> selectProcessingIds(@Param("limit") int limit);

    /**
     * 受理成功：created → processing（ADR-0006，transferToUserWallet 受理后写 payout_id + batch_id）。
     *
     * <p>WHERE 含 {@code status='created' AND version=#{version}} 双重 check → 原子 + 幂等：
     * affected=1 推进成功 / affected=0 已被并发推进或 version 漂移（幂等跳过）。batch_id UNIQUE 由 DB 兜底
     * （重复受理写同 batch_id 命中唯一约束，service 捕获按幂等处理，不二次转账）。</p>
     *
     * @param id       打款单 id
     * @param version  期望版本号
     * @param payoutId 微信侧转账单号
     * @param batchId  转账批次号（幂等关键）
     * @return 受影响行数（1 = 成功推进 / 0 = 已处理或并发，幂等跳过）
     */
    @Update("UPDATE gz_pay_payout_transaction " +
        "SET status = 'processing', payout_id = #{payoutId}, batch_id = #{batchId}, version = version + 1 " +
        "WHERE id = #{id} AND version = #{version} AND status = 'created' AND del_flag = '0'")
    int markProcessing(@Param("id") Long id,
                       @Param("version") Integer version,
                       @Param("payoutId") String payoutId,
                       @Param("batchId") String batchId);

    /**
     * 查单/回调确认到账：processing → success（终态，写 transferred_time，doc/11 附录 A.16）。
     *
     * <p>WHERE 含 {@code status='processing'} 守卫 → 原子 + 幂等（已非 processing 则 affected=0 跳过）。</p>
     *
     * @param id              打款单 id
     * @param transferredTime 转账成功时间
     * @return 受影响行数（1 = 推进成功 / 0 = 已非 processing，幂等跳过）
     */
    @Update("UPDATE gz_pay_payout_transaction " +
        "SET status = 'success', transferred_time = #{transferredTime}, version = version + 1 " +
        "WHERE id = #{id} AND status = 'processing' AND del_flag = '0'")
    int markSuccess(@Param("id") Long id, @Param("transferredTime") LocalDateTime transferredTime);

    /**
     * 查单/回调确认失败：processing → failed（旁路终态，写 fail_reason；可重试重置 created）。
     *
     * <p>WHERE 含 {@code status='processing'} 守卫 → 原子 + 幂等。</p>
     *
     * @param id         打款单 id
     * @param failReason 失败原因
     * @return 受影响行数（1 = 标记成功 / 0 = 已非 processing，幂等跳过）
     */
    @Update("UPDATE gz_pay_payout_transaction " +
        "SET status = 'failed', fail_reason = #{failReason}, version = version + 1 " +
        "WHERE id = #{id} AND status = 'processing' AND del_flag = '0'")
    int markFailed(@Param("id") Long id, @Param("failReason") String failReason);

    /**
     * 受理即失败：created → failed（旁路，transferToUserWallet 受理抛异常时 service 标失败）。
     *
     * @param id         打款单 id
     * @param failReason 失败原因
     * @return 受影响行数
     */
    @Update("UPDATE gz_pay_payout_transaction " +
        "SET status = 'failed', fail_reason = #{failReason}, version = version + 1 " +
        "WHERE id = #{id} AND status = 'created' AND del_flag = '0'")
    int markCreatedFailed(@Param("id") Long id, @Param("failReason") String failReason);

    /**
     * 失败重试：failed → created（ADR-0006 旁路 failed 可重试重置 created）。
     *
     * <p>WHERE 含 {@code status='failed'} 守卫 → 原子。重置时清 fail_reason / payout_id / batch_id
     * （重试将走新一轮受理生成新 batch_id）。</p>
     *
     * @param id 打款单 id
     * @return 受影响行数（1 = 重置成功 / 0 = 已非 failed）
     */
    @Update("UPDATE gz_pay_payout_transaction " +
        "SET status = 'created', fail_reason = NULL, payout_id = NULL, batch_id = NULL, version = version + 1 " +
        "WHERE id = #{id} AND status = 'failed' AND del_flag = '0'")
    int retryFailedToCreated(@Param("id") Long id);
}
