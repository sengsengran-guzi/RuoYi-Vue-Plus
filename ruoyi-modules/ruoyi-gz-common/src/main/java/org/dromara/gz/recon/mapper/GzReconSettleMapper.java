package org.dromara.gz.recon.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.gz.recon.domain.entity.GzReconSettle;
import org.dromara.gz.recon.domain.vo.GzReconSettleVo;

/**
 * gz_recon_settle 数据层（GZ-ADMIN-105）。
 *
 * <p>BaseMapperPlus 提供 selectVoList（admin 季度结算列表）；另暴露 {@link #upsert}
 * 按 UNIQUE(tenant_id, quarter) 幂等覆盖（季度跑批重跑不重复建单，AC5/R5）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-ADMIN-105)
 */
public interface GzReconSettleMapper extends BaseMapperPlus<GzReconSettle, GzReconSettleVo> {

    /**
     * 幂等 UPSERT：同 (tenant_id, quarter) 重跑 → 覆盖三项金额合计（不累加；保留 A 已录的支付/发票字段不动）。
     */
    @Select("""
        INSERT INTO gz_recon_settle
            (tenant_id, quarter,
             commission_total_cent, maintenance_total_cent, payable_total_cent,
             status, create_time)
        VALUES
            (#{row.tenantId}, #{row.quarter},
             #{row.commissionTotalCent}, #{row.maintenanceTotalCent}, #{row.payableTotalCent},
             #{row.status}, NOW())
        ON DUPLICATE KEY UPDATE
            commission_total_cent  = VALUES(commission_total_cent),
            maintenance_total_cent = VALUES(maintenance_total_cent),
            payable_total_cent     = VALUES(payable_total_cent),
            update_time            = NOW()
        """)
    int upsert(@Param("row") GzReconSettle row);
}
