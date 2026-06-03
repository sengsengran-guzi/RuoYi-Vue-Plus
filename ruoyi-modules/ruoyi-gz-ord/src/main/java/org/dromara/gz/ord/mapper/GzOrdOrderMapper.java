package org.dromara.gz.ord.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.gz.ord.domain.entity.GzOrdOrder;

import java.time.LocalDateTime;

/**
 * gz_ord_order 数据层（GZ-ORD-104）。
 *
 * <p>多租户 / 软删 / 分页由 ruoyi 拦截器自动处理。除 BaseMapperPlus CRUD 外，暴露行锁加载 +
 * 状态机原子推进方法（支付回调 onPaid created→paid / 用户取消 created→cancelled / 退款 paid→refunded），
 * 均带 status 守卫保证原子 + 幂等（与 gz_pay_transaction 同款，doc/10 §7.N8 / §7.E8 / §6.N10）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-ORD-104)
 */
public interface GzOrdOrderMapper extends BaseMapperPlus<GzOrdOrder, GzOrdOrder> {

    /**
     * 按 order_no 行级锁加载订单（支付回调 onPaid 用，{@code SELECT ... FOR UPDATE}）。
     *
     * <p>回调 SPI handler 按 order_no（= gz_pay_transaction.business_order_no）定位订单 + 行锁，
     * 与用户 cancel 互斥（doc/10 §7.E8 / 风险 R2）。必须在事务内调用。</p>
     *
     * @param orderNo 订单业务码
     * @return 锁定的订单行（无则 null）
     */
    @Select("SELECT * FROM gz_ord_order WHERE order_no = #{orderNo} AND del_flag = '0' LIMIT 1 FOR UPDATE")
    GzOrdOrder selectByOrderNoForUpdate(@Param("orderNo") String orderNo);

    /**
     * 按 id 行级锁加载订单（用户 cancel 用，{@code SELECT ... FOR UPDATE}）。
     *
     * @param id 订单 id
     * @return 锁定的订单行（无则 null）
     */
    @Select("SELECT * FROM gz_ord_order WHERE id = #{id} AND del_flag = '0' FOR UPDATE")
    GzOrdOrder selectByIdForUpdate(@Param("id") Long id);

    /**
     * 支付成功推进：created → paid（doc/10 §7.N8 回调 onPaid）。
     *
     * <p>WHERE 含 {@code business_status='created'} 守卫 → 原子 + 幂等：affected=1 本次推进成功 /
     * affected=0 已非 created（重复回调 / 已取消，幂等跳过）。回填 pay_transaction_id（微信交易号）+ paid_time。</p>
     *
     * @param id            订单 id
     * @param payTxnId      微信交易号（gz_pay_transaction.transaction_id）
     * @param paidTime      支付成功时间
     * @return 受影响行数（1 = 成功推进 / 0 = 已非 created，幂等跳过）
     */
    @Update("UPDATE gz_ord_order " +
        "SET business_status = 'paid', pay_transaction_id = #{payTxnId}, paid_time = #{paidTime}, " +
        "    version = version + 1 " +
        "WHERE id = #{id} AND business_status = 'created' AND del_flag = '0'")
    int markPaid(@Param("id") Long id,
                 @Param("payTxnId") String payTxnId,
                 @Param("paidTime") LocalDateTime paidTime);

    /**
     * 用户取消推进：created → cancelled（doc/10 §7.E8，仅 created 可达）。
     *
     * <p>WHERE 含 {@code business_status='created'} 守卫 → 原子 + 幂等：affected=1 取消成功（调用方据此回滚库存）/
     * affected=0 已非 created（已 paid / 已取消 —— 已 paid 由 service 提前判定返回 ORDER_NOT_CANCELLABLE）。</p>
     *
     * @param id            订单 id
     * @param cancelledTime 取消时间
     * @return 受影响行数（1 = 取消成功 / 0 = 已非 created）
     */
    @Update("UPDATE gz_ord_order " +
        "SET business_status = 'cancelled', cancelled_time = #{cancelledTime}, version = version + 1 " +
        "WHERE id = #{id} AND business_status = 'created' AND del_flag = '0'")
    int markCancelled(@Param("id") Long id, @Param("cancelledTime") LocalDateTime cancelledTime);

    /**
     * 退款推进：paid → refunded（doc/10 §6.N10 退款回调 onRefunded；SKU 库存不归还）。
     *
     * <p>WHERE 含 {@code business_status='paid'} 守卫 → 原子 + 幂等。退款回滚 SKU 库存<b>不归还</b>
     * （货已采购），故本方法只改订单状态，不触及 gz_ord_sku。</p>
     *
     * @param id 订单 id
     * @return 受影响行数（1 = 推进成功 / 0 = 已非 paid，幂等跳过）
     */
    @Update("UPDATE gz_ord_order " +
        "SET business_status = 'refunded', version = version + 1 " +
        "WHERE id = #{id} AND business_status = 'paid' AND del_flag = '0'")
    int markRefunded(@Param("id") Long id);
}
