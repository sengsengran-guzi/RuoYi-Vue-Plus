package org.dromara.gz.recon.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.gz.recon.domain.entity.GzReconDaily;
import org.dromara.gz.recon.domain.vo.GzReconDailyVo;
import org.dromara.gz.recon.domain.vo.ReconSummaryVo;

/**
 * gz_recon_daily 数据层（GZ-ADMIN-105）。
 *
 * <p>BaseMapperPlus 提供 selectVoPage / selectVoList（admin 明细查询）；另暴露：</p>
 * <ul>
 *   <li>{@link #upsert}：按 UNIQUE(tenant_id, business_day, business_type) 幂等覆盖（同日重跑不累加，AC6/R5）；</li>
 *   <li>{@link #sumDailyByMonth}：月度跑批聚合当月各业务线 daily（doc/11 §9.2 源）。</li>
 * </ul>
 *
 * @author kevin-coder (sensenran-guzi · GZ-ADMIN-105)
 */
public interface GzReconDailyMapper extends BaseMapperPlus<GzReconDaily, GzReconDailyVo> {

    /**
     * 幂等 UPSERT：同 (tenant_id, business_day, business_type) 重跑 → 覆盖 4 个金额 + status（不累加）。
     * <p>跑批在 TenantHelper.ignore 下执行，tenant_id 由 service 显式置 '1001' 落库。</p>
     */
    @Select("""
        INSERT INTO gz_recon_daily
            (tenant_id, business_day, business_type,
             system_gmv_cent, system_refund_cent, system_fee_cent, system_settle_cent,
             status, create_time)
        VALUES
            (#{row.tenantId}, #{row.businessDay}, #{row.businessType},
             #{row.systemGmvCent}, #{row.systemRefundCent}, #{row.systemFeeCent}, #{row.systemSettleCent},
             #{row.status}, NOW())
        ON DUPLICATE KEY UPDATE
            system_gmv_cent    = VALUES(system_gmv_cent),
            system_refund_cent = VALUES(system_refund_cent),
            system_fee_cent    = VALUES(system_fee_cent),
            system_settle_cent = VALUES(system_settle_cent),
            status             = VALUES(status),
            update_time        = NOW()
        """)
    int upsert(@Param("row") GzReconDaily row);

    /**
     * 月度聚合：某业务线某月（yyyy-MM）SUM(daily 三金额)。settle/commission 由 service + ReconCalculator 计算。
     * <p>列别名经 mapUnderscoreToCamelCase 映射到 {@link ReconSummaryVo}（gmvCent/refundCent/channelFeeCent）。</p>
     */
    @Select("""
        SELECT COALESCE(SUM(system_gmv_cent), 0)    AS gmv_cent,
               COALESCE(SUM(system_refund_cent), 0) AS refund_cent,
               COALESCE(SUM(system_fee_cent), 0)    AS channel_fee_cent
        FROM gz_recon_daily
        WHERE tenant_id = '1001' AND del_flag = '0'
          AND business_type = #{businessType}
          AND DATE_FORMAT(business_day, '%Y-%m') = #{businessMonth}
        """)
    ReconSummaryVo sumDailyByMonth(@Param("businessType") String businessType,
                                   @Param("businessMonth") String businessMonth);
}
