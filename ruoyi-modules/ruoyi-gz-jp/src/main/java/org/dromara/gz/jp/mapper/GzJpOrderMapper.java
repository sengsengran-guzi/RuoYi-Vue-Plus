package org.dromara.gz.jp.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.gz.jp.domain.entity.GzJpOrder;

import java.time.LocalDateTime;

/**
 * gz_jp_order 数据层（GZ-JP-105）。
 *
 * <p>多租户由 {@code TenantLineInnerInterceptor} 自动 append {@code WHERE tenant_id = ?}
 * （自定义 {@code @Select} / {@code @Update} 同样生效）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-JP-105)
 */
public interface GzJpOrderMapper extends BaseMapperPlus<GzJpOrder, GzJpOrder> {

    /**
     * 按 order_no <b>行级锁</b>加载订单（支付回调 {@code onPaid} 用）。
     *
     * <p>{@code SELECT ... FOR UPDATE} 与「客人取消 / 并发的第二次回调」互斥 ——
     * 先锁行再判状态再推进，避免「两个回调同时读到 created 都去 markPaid」。
     * 真正的幂等仍靠 {@link #markPaid} 的 WHERE 状态守卫（锁只是把竞争串行化）。</p>
     *
     * @param orderNo 订单号（= gz_pay_transaction.business_order_no）
     * @return 锁定的订单行；不存在返回 null
     */
    @Select("SELECT * FROM gz_jp_order WHERE order_no = #{orderNo} AND del_flag = '0' FOR UPDATE")
    GzJpOrder selectByOrderNoForUpdate(@Param("orderNo") String orderNo);

    /**
     * 支付成功推进：created → paid（FLOW:F-JP-02.step6）。
     *
     * <p><b>WHERE 含 {@code business_status='created'} 守卫 → 原子 + 幂等</b>：
     * affected=1 本次推进成功（调用方据此才去激活商品行）；
     * affected=0 已非 created（重复回调 / 并发抢先），调用方直接跳过，
     * <b>绝不重复推进商品行</b>（AC「支付回调幂等」的落点就在这个返回值上）。</p>
     *
     * <p>{@code pay_transaction_id} 写的是 <b>{@code gz_pay_transaction.id}</b>（本地流水行主键），
     * 不是微信的 transaction_id 字符串 —— 后者在流水表里，不必在业务表冗余第二份。</p>
     *
     * @param id           订单 id
     * @param payTxnId     gz_pay_transaction.id
     * @param paidTime     支付时间（微信回调给的时间）
     * @return 受影响行数（1 = 本次推进成功 / 0 = 已非 created，幂等跳过）
     */
    @Update("UPDATE gz_jp_order SET business_status = 'paid', pay_transaction_id = #{payTxnId}, "
        + "paid_time = #{paidTime}, version = version + 1, update_time = NOW() "
        + "WHERE id = #{id} AND business_status = 'created' AND del_flag = '0'")
    int markPaid(@Param("id") Long id,
                 @Param("payTxnId") Long payTxnId,
                 @Param("paidTime") LocalDateTime paidTime);

    /**
     * 取消推进：created → cancelled（客人主动取消 / 超时关单兜底）。
     *
     * <p>同样带状态守卫，affected=0 表示已非 created（已支付 / 已取消），调用方按不可取消处理。</p>
     *
     * @param id            订单 id
     * @param cancelledTime 取消时间
     * @return 受影响行数（1 = 成功 / 0 = 已非 created）
     */
    @Update("UPDATE gz_jp_order SET business_status = 'cancelled', cancelled_time = #{cancelledTime}, "
        + "version = version + 1, update_time = NOW() "
        + "WHERE id = #{id} AND business_status = 'created' AND del_flag = '0'")
    int markCancelled(@Param("id") Long id, @Param("cancelledTime") LocalDateTime cancelledTime);
}
