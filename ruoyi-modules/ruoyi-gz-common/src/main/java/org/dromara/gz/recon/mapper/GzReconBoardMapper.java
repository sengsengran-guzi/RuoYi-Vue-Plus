package org.dromara.gz.recon.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.dromara.gz.recon.domain.vo.GzPindouBoardVo;
import org.dromara.gz.recon.domain.vo.GzRecycleBoardVo;

import java.time.LocalDate;
import java.util.List;

/**
 * 对账中心「记账台账」板块数据源 mapper（拼豆收款 + 回收打款，<b>纯展示、不计 4% 分成</b>）。
 *
 * <p><b>与 {@link GzReconSourceMapper} 的边界</b>：Source mapper 服务于合同 §4.1 的 4% 分成跑批
 * （只查 preorder/gacha，结果落 gz_recon_daily/monthly，是分成权威表）。本 mapper 独立，只做拼豆 /
 * 回收的<b>实时只读聚合</b>，<b>不写任何分成表</b>，杜绝把不计分成的业务掺进分成核算。</p>
 *
 * <p><b>显式带 tenant_id='1001' + del_flag='0'</b>：admin 登录态本已按租户过滤，此处显式冗余保证
 * 即便未来在 {@code TenantHelper.ignore} 下复用也不串租户（与 Source mapper 一致，CLAUDE.md §6 #2）。</p>
 *
 * @author kevin-coder (sensenran-guzi)
 */
public interface GzReconBoardMapper {

    /**
     * 拼豆逐月收款（按 paid_time 归月）：填 {@code month / gmvCent / channelFeeCent / paidCount}。
     * businessType 固定传 'pindou'，保留形参便于将来扩展别的不计分成业务线。
     */
    @Select("""
        SELECT DATE_FORMAT(paid_time, '%Y-%m') AS `month`,
               COALESCE(SUM(amount_cent), 0)   AS gmvCent,
               COALESCE(SUM(fee_cent), 0)      AS channelFeeCent,
               COUNT(*)                        AS paidCount
        FROM gz_pay_transaction
        WHERE tenant_id = '1001' AND del_flag = '0'
          AND business_type = #{businessType}
          AND status = 'paid'
          AND DATE(paid_time) BETWEEN #{startDate} AND #{endDate}
        GROUP BY DATE_FORMAT(paid_time, '%Y-%m')
        """)
    List<GzPindouBoardVo.MonthRow> sumPaidByMonth(@Param("businessType") String businessType,
                                                  @Param("startDate") LocalDate startDate,
                                                  @Param("endDate") LocalDate endDate);

    /**
     * 拼豆逐月退款（按 refunded_time 归月，C4 口径）：填 {@code month / refundCent / refundCount}。
     * business_type 由 JOIN transaction 取得。
     */
    @Select("""
        SELECT DATE_FORMAT(r.refunded_time, '%Y-%m')  AS `month`,
               COALESCE(SUM(r.refund_amount_cent), 0) AS refundCent,
               COUNT(*)                               AS refundCount
        FROM gz_pay_refund r
        JOIN gz_pay_transaction t
          ON t.out_trade_no = r.out_trade_no AND t.tenant_id = r.tenant_id
        WHERE r.tenant_id = '1001' AND r.del_flag = '0'
          AND t.business_type = #{businessType}
          AND r.status = 'refunded'
          AND r.refunded_time IS NOT NULL
          AND DATE(r.refunded_time) BETWEEN #{startDate} AND #{endDate}
        GROUP BY DATE_FORMAT(r.refunded_time, '%Y-%m')
        """)
    List<GzPindouBoardVo.MonthRow> sumRefundByMonth(@Param("businessType") String businessType,
                                                    @Param("startDate") LocalDate startDate,
                                                    @Param("endDate") LocalDate endDate);

    /**
     * 回收逐月成功打款（按 transferred_time 归月）：填 {@code month / payoutCent / payoutCount}。
     */
    @Select("""
        SELECT DATE_FORMAT(transferred_time, '%Y-%m') AS `month`,
               COALESCE(SUM(amount_cent), 0)          AS payoutCent,
               COUNT(*)                               AS payoutCount
        FROM gz_pay_payout_transaction
        WHERE tenant_id = '1001' AND del_flag = '0'
          AND business_type = 'recycle'
          AND status = 'success'
          AND transferred_time IS NOT NULL
          AND DATE(transferred_time) BETWEEN #{startDate} AND #{endDate}
        GROUP BY DATE_FORMAT(transferred_time, '%Y-%m')
        """)
    List<GzRecycleBoardVo.MonthRow> sumPayoutSuccessByMonth(@Param("startDate") LocalDate startDate,
                                                            @Param("endDate") LocalDate endDate);

    /**
     * 回收当期待办笔数（处理中 / 失败，按 create_time 计入区间）：映射到 {@link GzRecycleBoardVo}
     * 的 {@code processingCount / failedCount}（其余字段留默认 0，由 service 填充）。
     */
    @Select("""
        SELECT COALESCE(SUM(CASE WHEN status = 'processing' THEN 1 ELSE 0 END), 0) AS processingCount,
               COALESCE(SUM(CASE WHEN status = 'failed'     THEN 1 ELSE 0 END), 0) AS failedCount
        FROM gz_pay_payout_transaction
        WHERE tenant_id = '1001' AND del_flag = '0'
          AND business_type = 'recycle'
          AND DATE(create_time) BETWEEN #{startDate} AND #{endDate}
        """)
    GzRecycleBoardVo countPayoutBacklog(@Param("startDate") LocalDate startDate,
                                        @Param("endDate") LocalDate endDate);
}
