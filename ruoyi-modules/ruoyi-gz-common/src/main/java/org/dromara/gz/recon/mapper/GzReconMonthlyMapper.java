package org.dromara.gz.recon.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.gz.recon.domain.entity.GzReconMonthly;
import org.dromara.gz.recon.domain.vo.GzReconMonthlyVo;

import java.util.List;

/**
 * gz_recon_monthly 数据层（GZ-ADMIN-105）。
 *
 * <p>BaseMapperPlus 提供 selectVoList / selectList（admin 月度对账单 + summary 聚合）；另暴露：</p>
 * <ul>
 *   <li>{@link #upsert}：按 UNIQUE(tenant_id, business_month, business_type) 幂等覆盖；</li>
 *   <li>{@link #sumCommissionByMonths}：季度结算汇总当季 3 月分成（A+B 自然相加，合同 §4.2.3）。</li>
 * </ul>
 *
 * @author kevin-coder (sensenran-guzi · GZ-ADMIN-105)
 */
public interface GzReconMonthlyMapper extends BaseMapperPlus<GzReconMonthly, GzReconMonthlyVo> {

    /**
     * 幂等 UPSERT：同 (tenant_id, business_month, business_type) 重跑 → 覆盖金额/分成/status（不累加）。
     */
    @Select("""
        INSERT INTO gz_recon_monthly
            (tenant_id, business_month, business_type,
             gmv_cent, refund_cent, channel_fee_cent, settle_cent,
             commission_rate_bp, commission_cent, status, create_time)
        VALUES
            (#{row.tenantId}, #{row.businessMonth}, #{row.businessType},
             #{row.gmvCent}, #{row.refundCent}, #{row.channelFeeCent}, #{row.settleCent},
             #{row.commissionRateBp}, #{row.commissionCent}, #{row.status}, NOW())
        ON DUPLICATE KEY UPDATE
            gmv_cent           = VALUES(gmv_cent),
            refund_cent        = VALUES(refund_cent),
            channel_fee_cent   = VALUES(channel_fee_cent),
            settle_cent        = VALUES(settle_cent),
            commission_rate_bp = VALUES(commission_rate_bp),
            commission_cent    = VALUES(commission_cent),
            status             = VALUES(status),
            update_time        = NOW()
        """)
    int upsert(@Param("row") GzReconMonthly row);

    /**
     * 季度分成合计（分）= SUM(commission_cent WHERE business_month IN (当季 3 月))。
     * <p>不分 business_type → A + B 自然相加（合同 §4.2.3 季度合并支付）。</p>
     */
    @Select("""
        <script>
        SELECT COALESCE(SUM(commission_cent), 0)
        FROM gz_recon_monthly
        WHERE tenant_id = '1001' AND del_flag = '0'
          AND business_month IN
          <foreach collection="months" item="m" open="(" separator="," close=")">#{m}</foreach>
        </script>
        """)
    long sumCommissionByMonths(@Param("months") List<String> months);
}
