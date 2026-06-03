package org.dromara.gz.common.pay.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.gz.common.pay.domain.entity.GzPayRefund;
import org.dromara.gz.common.pay.domain.vo.GzPayRefundVO;

import java.time.LocalDateTime;

/**
 * gz_pay_refund 数据层（GZ-PAY-103）。
 *
 * <p>多租户 / 软删由 ruoyi 拦截器自动处理。除 BaseMapperPlus CRUD 外，暴露手写方法承载退款状态机
 * 原子推进（回调幂等，doc/10 §6.N10）+ refund_no 序号生成 + 防重复退查询。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-PAY-103)
 */
public interface GzPayRefundMapper extends BaseMapperPlus<GzPayRefund, GzPayRefundVO> {

    /**
     * 统计同一 transaction_id 下处于 refunding / refunded 的退款单数（防重复退，AC 2）。
     *
     * <p>同一笔支付仅允许一次成功退款（仅全额）：已有 refunding（处理中）或 refunded（已成功）→ 拒绝再发起。
     * failed 退款单不计入（受理失败 / 回调失败可重新发起）。</p>
     *
     * @param transactionId 微信交易号
     * @return 非终态退款单计数（&gt; 0 表示已有退款进行中 / 已完成）
     */
    @Select("SELECT COUNT(*) FROM gz_pay_refund " +
        "WHERE transaction_id = #{transactionId} AND status IN ('refunding', 'refunded') AND del_flag = '0'")
    long countActiveByTransactionId(@Param("transactionId") String transactionId);

    /**
     * 按 refund_no 行级锁加载退款单（退款回调 SELECT ... FOR UPDATE，doc/10 §6.N10）。
     *
     * <p>必须在事务内调用；锁定后行被并发回调阻塞直到本事务提交（防双推进）。</p>
     *
     * @param refundNo 商户退款单号
     * @return 锁定的退款单行（无则 null）
     */
    @Select("SELECT * FROM gz_pay_refund WHERE refund_no = #{refundNo} AND del_flag = '0' FOR UPDATE")
    GzPayRefund selectByRefundNoForUpdate(@Param("refundNo") String refundNo);

    /**
     * 退款回调 SUCCESS：refunding → refunded（条件 UPDATE，AC 3 幂等核心）。
     *
     * <p>WHERE 含 {@code status='refunding'} 守卫 → 原子 + 幂等：affected=1 推进成功 /
     * affected=0 已被并发回调改 refunded / 已 failed（跳过）。同时写 wechat_refund_id + refunded_time。</p>
     *
     * @param id             退款单 id
     * @param wechatRefundId 微信退款单号
     * @param refundedTime   退款完成时间
     * @return 受影响行数（1 = 推进成功 / 0 = 已非 refunding，幂等跳过）
     */
    @Update("UPDATE gz_pay_refund " +
        "SET status = 'refunded', wechat_refund_id = #{wechatRefundId}, refunded_time = #{refundedTime} " +
        "WHERE id = #{id} AND status = 'refunding' AND del_flag = '0'")
    int markRefunded(@Param("id") Long id,
                     @Param("wechatRefundId") String wechatRefundId,
                     @Param("refundedTime") LocalDateTime refundedTime);

    /**
     * 退款受理失败 / 回调 ABNORMAL/CLOSED：refunding → failed（条件 UPDATE，AC 2/3）。
     *
     * <p>WHERE 含 {@code status='refunding'} 守卫 → 原子 + 幂等。受理失败时 wechat_refund_id 仍为 NULL
     * （传 null）；回调失败时落微信退款单号供人工追溯。failed 后留 admin 人工介入（doc/10 §6.E4）。</p>
     *
     * @param id             退款单 id
     * @param wechatRefundId 微信退款单号（受理失败为 null）
     * @return 受影响行数（1 = 标记成功 / 0 = 已非 refunding，幂等跳过）
     */
    @Update("UPDATE gz_pay_refund " +
        "SET status = 'failed', wechat_refund_id = COALESCE(#{wechatRefundId}, wechat_refund_id) " +
        "WHERE id = #{id} AND status = 'refunding' AND del_flag = '0'")
    int markFailed(@Param("id") Long id, @Param("wechatRefundId") String wechatRefundId);

    /**
     * 取当日已生成的最大 refund_no 序号（refund_no = RF-yyyyMMdd-6位序号 生成用，决策 D3）。
     *
     * <p>与 PAY-001 out_trade_no 同款 DB MAX+1 策略；并发由 UNIQUE(tenant_id, refund_no) 兜底重试。</p>
     *
     * @param prefixDate 形如 "RF-20260606-"（前缀 + 日期 + 连字符）
     * @return 当日最大序号（无则 0）
     */
    @Select("SELECT COALESCE(MAX(CAST(SUBSTRING(refund_no, LENGTH(#{prefixDate}) + 1) AS UNSIGNED)), 0) " +
        "FROM gz_pay_refund WHERE refund_no LIKE CONCAT(#{prefixDate}, '%')")
    long selectMaxDailySeq(@Param("prefixDate") String prefixDate);
}
