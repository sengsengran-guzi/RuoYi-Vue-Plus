package org.dromara.gz.recon.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.dromara.gz.recon.domain.vo.ReconDetailRowVo;

import java.time.LocalDate;
import java.util.List;

/**
 * 对账数据源 mapper（GZ-ADMIN-105）—— 只读聚合 gz_pay_transaction / gz_pay_refund。
 *
 * <p>跑批口径权威 doc/11 §9.1（逐字落地，零容忍）：</p>
 * <ul>
 *   <li>GMV / 通道费：gz_pay_transaction status='paid' 且 DATE(paid_time)=业务日，按 business_type；</li>
 *   <li>退款：gz_pay_refund JOIN transaction 取 business_type，按 DATE(refunded_time)=业务日归属（C4，不追溯成交日）；</li>
 *   <li>test/pindou 单天然不进（仅查 preorder/gacha 入参）。</li>
 * </ul>
 *
 * <p><b>显式带 tenant_id='1001' + del_flag='0'</b>：跑批在 {@code TenantHelper.ignore} 下执行，
 * 租户拦截器被绕过，必须 SQL 显式过滤（与 dashboard mapper 一致）。V1.1 单租户固定 '1001'（CLAUDE.md §6 #2）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-ADMIN-105)
 */
public interface GzReconSourceMapper {

    /**
     * 某业务线某日 GMV（分）= SUM(amount_cent WHERE business_type AND status='paid' AND DATE(paid_time)=day)。
     */
    @Select("""
        SELECT COALESCE(SUM(amount_cent), 0)
        FROM gz_pay_transaction
        WHERE tenant_id = '1001' AND del_flag = '0'
          AND business_type = #{businessType}
          AND status = 'paid'
          AND DATE(paid_time) = #{businessDay}
        """)
    long sumGmvCent(@Param("businessType") String businessType, @Param("businessDay") LocalDate businessDay);

    /**
     * 某业务线某日通道费（分）= SUM(fee_cent WHERE business_type AND status='paid' AND DATE(paid_time)=day)。
     * <p>fee_cent 来自 PAY-104 拉 fundflowbill 回写；NULL 由 SUM 忽略 + COALESCE 兜底 0。</p>
     */
    @Select("""
        SELECT COALESCE(SUM(fee_cent), 0)
        FROM gz_pay_transaction
        WHERE tenant_id = '1001' AND del_flag = '0'
          AND business_type = #{businessType}
          AND status = 'paid'
          AND DATE(paid_time) = #{businessDay}
        """)
    long sumFeeCent(@Param("businessType") String businessType, @Param("businessDay") LocalDate businessDay);

    /**
     * 某业务线某日退款（分）= SUM(r.refund_amount_cent WHERE 关联 t.business_type AND DATE(r.refunded_time)=day)。
     * <p>退款按 refunded_time 当日归属（C4，不追溯成交日）；business_type 由 JOIN transaction 取得。</p>
     */
    @Select("""
        SELECT COALESCE(SUM(r.refund_amount_cent), 0)
        FROM gz_pay_refund r
        JOIN gz_pay_transaction t
          ON t.out_trade_no = r.out_trade_no AND t.tenant_id = r.tenant_id
        WHERE r.tenant_id = '1001' AND r.del_flag = '0'
          AND t.business_type = #{businessType}
          AND r.status = 'refunded'
          AND r.refunded_time IS NOT NULL
          AND DATE(r.refunded_time) = #{businessDay}
        """)
    long sumRefundCent(@Param("businessType") String businessType, @Param("businessDay") LocalDate businessDay);

    /**
     * Excel 导出明细：某业务线在 [startDate, endDate]（按 paid_time 日期）每笔已支付交易一行，
     * LEFT JOIN 已完成退款补退款金额/时间（合同 §4.2.1 每笔颗粒度）。
     *
     * <p>列经 mapUnderscoreToCamelCase 自动映射到 {@link ReconDetailRowVo}；按 paid_time 正序。</p>
     */
    @Select("""
        SELECT t.out_trade_no, t.business_order_no, t.business_type,
               t.amount_cent, t.fee_cent, t.paid_time, t.status,
               r.refund_amount_cent, r.refunded_time
        FROM gz_pay_transaction t
        LEFT JOIN gz_pay_refund r
          ON r.out_trade_no = t.out_trade_no AND r.tenant_id = t.tenant_id
         AND r.del_flag = '0' AND r.status = 'refunded'
        WHERE t.tenant_id = '1001' AND t.del_flag = '0'
          AND t.business_type = #{businessType}
          AND t.status = 'paid'
          AND DATE(t.paid_time) BETWEEN #{startDate} AND #{endDate}
        ORDER BY t.paid_time ASC
        """)
    List<ReconDetailRowVo> selectDetailRows(@Param("businessType") String businessType,
                                            @Param("startDate") LocalDate startDate,
                                            @Param("endDate") LocalDate endDate);
}
