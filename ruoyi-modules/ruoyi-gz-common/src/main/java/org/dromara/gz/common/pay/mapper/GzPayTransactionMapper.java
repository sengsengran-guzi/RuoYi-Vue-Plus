package org.dromara.gz.common.pay.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.gz.common.pay.domain.entity.GzPayTransaction;
import org.dromara.gz.common.pay.domain.vo.GzPayTransactionVO;

import java.time.LocalDateTime;
import java.util.List;

/**
 * gz_pay_transaction 数据层（GZ-PAY-001）。
 *
 * <p>多租户 / 软删由 ruoyi 拦截器自动处理。除 BaseMapperPlus CRUD 外，暴露三个手写方法承载
 * 幂等回调（AC 7）与超时关单（AC 8）的状态机原子推进。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-PAY-001)
 */
public interface GzPayTransactionMapper extends BaseMapperPlus<GzPayTransaction, GzPayTransactionVO> {

    /**
     * 按 out_trade_no 查订单（回调 / mp 轮询 / 幂等 SELECT 用，至多 1 条）。
     *
     * @param outTradeNo 业务订单号
     * @return 命中订单（无则 null）
     */
    @Select("SELECT * FROM gz_pay_transaction WHERE out_trade_no = #{outTradeNo} AND del_flag = '0' LIMIT 1")
    GzPayTransaction selectByOutTradeNo(@Param("outTradeNo") String outTradeNo);

    /**
     * 按微信 transaction_id 查订单（幂等兜底：回调先用 transaction_id 查重）。
     *
     * @param transactionId 微信交易号
     * @return 命中订单（无则 null）
     */
    @Select("SELECT * FROM gz_pay_transaction WHERE transaction_id = #{transactionId} AND del_flag = '0' LIMIT 1")
    GzPayTransaction selectByTransactionId(@Param("transactionId") String transactionId);

    /**
     * 乐观锁 + 状态守卫 条件 UPDATE：pending → paid（AC 7 幂等核心）。
     *
     * <p>WHERE 含 {@code version=#{version} AND status='pending'} 双重 check（doc/10 §2.N7）：</p>
     * <ul>
     *   <li>affected=1 → 本次成功推进（之前确为 pending 且 version 匹配）</li>
     *   <li>affected=0 → 已被并发回调改成 paid（重复回调）或 version 漂移 → 调用方按 duplicated 处理（幂等）</li>
     * </ul>
     *
     * <p>不依赖 mybatis-plus @Version 自动机制，显式手写 SQL 保证 status 守卫与 version 守卫同时生效。</p>
     *
     * @param id            订单 id
     * @param version       期望版本号
     * @param transactionId 微信交易号
     * @param feeCent       通道手续费（分，回调提取，可 null）
     * @param paidTime      支付成功时间
     * @return 受影响行数（1 = 成功推进 / 0 = 已处理或并发，幂等跳过）
     */
    @Update("UPDATE gz_pay_transaction " +
        "SET status = 'paid', transaction_id = #{transactionId}, fee_cent = #{feeCent}, " +
        "    paid_time = #{paidTime}, version = version + 1 " +
        "WHERE id = #{id} AND version = #{version} AND status = 'pending' AND del_flag = '0'")
    int markPaid(@Param("id") Long id,
                 @Param("version") Integer version,
                 @Param("transactionId") String transactionId,
                 @Param("feeCent") Long feeCent,
                 @Param("paidTime") LocalDateTime paidTime);

    /**
     * 写 prepay_id 并把 created → pending（AC 4 统一下单成功后）。
     *
     * @param id        订单 id
     * @param prepayId  微信 prepay_id
     * @return 受影响行数
     */
    @Update("UPDATE gz_pay_transaction " +
        "SET status = 'pending', prepay_id = #{prepayId}, version = version + 1 " +
        "WHERE id = #{id} AND status = 'created' AND del_flag = '0'")
    int markPending(@Param("id") Long id, @Param("prepayId") String prepayId);

    /**
     * 扫超时未支付订单 id（AC 8 SnailJob 超时关单：status='pending' AND expire_time &lt; now）。
     *
     * <p>不分页全量取 id（V1.0 测试单量极小），由 service 逐条条件 UPDATE 标 timeout。
     * 多租户由拦截器处理；cron 上下文用 TenantHelper.ignore 全租户扫（与 GZ-BEAN-009 同思路）。</p>
     *
     * @param now 当前时间
     * @return 待标 timeout 的订单 id 列表
     */
    @Select("SELECT id FROM gz_pay_transaction " +
        "WHERE status = 'pending' AND expire_time IS NOT NULL AND expire_time < #{now} AND del_flag = '0' " +
        "ORDER BY id")
    List<Long> selectExpiredPendingIds(@Param("now") LocalDateTime now);

    /**
     * 条件 UPDATE 标记单条订单超时（AC 8）。
     *
     * <p>WHERE 含 {@code status='pending'} 守卫 → 天然原子 + 幂等：affected=1 标记成功 /
     * affected=0 已被回调改 paid 或上轮 cron 已标（跳过）。</p>
     *
     * @param id          订单 id
     * @param closedTime  关单时间
     * @return 受影响行数（1 = 标记成功 / 0 = 已非 pending，幂等跳过）
     */
    @Update("UPDATE gz_pay_transaction " +
        "SET status = 'timeout', closed_time = #{closedTime}, version = version + 1 " +
        "WHERE id = #{id} AND status = 'pending' AND del_flag = '0'")
    int markTimeout(@Param("id") Long id, @Param("closedTime") LocalDateTime closedTime);

    /**
     * 扫主动查单兜底窗口内的 pending 订单 id（GZ-PAY-102 AC 2，doc/10 §6.N7）。
     *
     * <p>条件：{@code status='pending'} AND 创建时间在 [now-24h, now-5min]（超 5min 才查，避免和
     * 正常回调抢；超 24h 老单不查，由 AC 8 关单逻辑兜底）+ {@code LIMIT 100}（防雪崩，单轮控量）。
     * 多租户由 service 用 {@code TenantHelper.ignore} 全租户扫（cron 无登录态，与超时关单同思路）。</p>
     *
     * @param windowStart 窗口下界（now - 24h）
     * @param windowEnd   窗口上界（now - 5min）
     * @param limit       单轮上限（100）
     * @return 待查单的 pending 订单 id 列表
     */
    @Select("SELECT id FROM gz_pay_transaction " +
        "WHERE status = 'pending' AND del_flag = '0' " +
        "AND create_time >= #{windowStart} AND create_time < #{windowEnd} " +
        "ORDER BY id LIMIT #{limit}")
    List<Long> selectReconcilePendingIds(@Param("windowStart") LocalDateTime windowStart,
                                         @Param("windowEnd") LocalDateTime windowEnd,
                                         @Param("limit") int limit);

    /**
     * 行级锁加载订单（GZ-PAY-102 AC 4 / AC 5，{@code SELECT ... FOR UPDATE}）。
     *
     * <p>主动查单补单在锁行后做状态判定 + 推进，与被动回调走同一幂等基础（强约束 #1）。
     * 必须在事务内调用；锁定后行被并发查单 / 回调阻塞直到本事务提交（防双推进）。</p>
     *
     * @param id 订单 id
     * @return 锁定的订单行（无则 null）
     */
    @Select("SELECT * FROM gz_pay_transaction WHERE id = #{id} AND del_flag = '0' FOR UPDATE")
    GzPayTransaction selectByIdForUpdate(@Param("id") Long id);

    /**
     * 条件 UPDATE 标记订单关闭（GZ-PAY-102 AC 7：微信侧 CLOSED / REVOKED → 本地 closed）。
     *
     * <p>WHERE 含 {@code status='pending'} 守卫 → 原子 + 幂等（已非 pending 则 affected=0 跳过）。</p>
     *
     * @param id         订单 id
     * @param closedTime 关闭时间
     * @return 受影响行数（1 = 标记成功 / 0 = 已非 pending，幂等跳过）
     */
    @Update("UPDATE gz_pay_transaction " +
        "SET status = 'closed', closed_time = #{closedTime}, version = version + 1 " +
        "WHERE id = #{id} AND status = 'pending' AND del_flag = '0'")
    int markClosed(@Param("id") Long id, @Param("closedTime") LocalDateTime closedTime);

    /**
     * 条件 UPDATE 标记订单失败（GZ-PAY-102 AC 7：微信侧 PAYERROR → 本地 failed）。
     *
     * <p>WHERE 含 {@code status='pending'} 守卫 → 原子 + 幂等。failed 态不写 closed_time（非关单语义）。</p>
     *
     * @param id 订单 id
     * @return 受影响行数（1 = 标记成功 / 0 = 已非 pending，幂等跳过）
     */
    @Update("UPDATE gz_pay_transaction " +
        "SET status = 'failed', version = version + 1 " +
        "WHERE id = #{id} AND status = 'pending' AND del_flag = '0'")
    int markFailed(@Param("id") Long id);

    /**
     * 退款申请：paid → refunding（GZ-PAY-103 AC 2，条件 UPDATE 原子推进）。
     *
     * <p>WHERE 含 {@code status='paid'} 守卫 → 原子：affected=1 推进成功 / affected=0 已非 paid
     * （已退款 / 已 refunding 等，防并发重复发起，与 countActiveByTransactionId 双重防护）。</p>
     *
     * @param id 支付交易行 id
     * @return 受影响行数（1 = 成功推进 refunding / 0 = 已非 paid，拒绝退款）
     */
    @Update("UPDATE gz_pay_transaction " +
        "SET status = 'refunding', version = version + 1 " +
        "WHERE id = #{id} AND status = 'paid' AND del_flag = '0'")
    int markRefunding(@Param("id") Long id);

    /**
     * 退款回调 SUCCESS：refunding → refunded（GZ-PAY-103 AC 3）。
     *
     * <p>WHERE 含 {@code status='refunding'} 守卫 → 原子 + 幂等。</p>
     *
     * @param id 支付交易行 id
     * @return 受影响行数（1 = 推进成功 / 0 = 已非 refunding，幂等跳过）
     */
    @Update("UPDATE gz_pay_transaction " +
        "SET status = 'refunded', version = version + 1 " +
        "WHERE id = #{id} AND status = 'refunding' AND del_flag = '0'")
    int markRefunded(@Param("id") Long id);

    /**
     * 退款受理失败回滚：refunding → paid（GZ-PAY-103 AC 2，doc/10 §6.E4）。
     *
     * <p>WHERE 含 {@code status='refunding'} 守卫 → 原子。微信受理失败时把交易行回滚回 paid，
     * 允许 admin 重新发起退款。</p>
     *
     * @param id 支付交易行 id
     * @return 受影响行数（1 = 回滚成功 / 0 = 已非 refunding）
     */
    @Update("UPDATE gz_pay_transaction " +
        "SET status = 'paid', version = version + 1 " +
        "WHERE id = #{id} AND status = 'refunding' AND del_flag = '0'")
    int rollbackRefundingToPaid(@Param("id") Long id);

    /**
     * 取当日某业务类型已生成的最大日内序号（out_trade_no 生成用，doc/10 §6 Q6.3）。
     *
     * <p>out_trade_no 格式 {@code <PREFIX>-yyyyMMdd-6位序号}，按 prefix + 日期 LIKE 取当日最大序号 + 1。
     * 与 GZ-BEAN booking_no 同款 DB MAX+1 策略（V1.0 量级足够；并发由 out_trade_no UNIQUE 兜底重试）。</p>
     *
     * @param prefixDate 形如 "TEST-20260604-"（前缀 + 日期 + 连字符）
     * @return 当日最大序号（无则 0）
     */
    @Select("SELECT COALESCE(MAX(CAST(SUBSTRING(out_trade_no, LENGTH(#{prefixDate}) + 1) AS UNSIGNED)), 0) " +
        "FROM gz_pay_transaction WHERE out_trade_no LIKE CONCAT(#{prefixDate}, '%')")
    long selectMaxDailySeq(@Param("prefixDate") String prefixDate);
}
