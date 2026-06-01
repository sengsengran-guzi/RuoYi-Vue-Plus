package org.dromara.gz.common.dashboard.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.gz.common.dashboard.domain.entity.GzDashboardSnapshot;
import org.dromara.gz.common.dashboard.domain.vo.GzDashboardTrendVO;

import java.time.LocalDateTime;
import java.util.List;

/**
 * gz_dashboard_snapshot 数据层（GZ-ADMIN-003）。
 *
 * <p>写快照走 BaseMapperPlus {@code insertBatch}；查 latest / trend 用手写 SQL。
 * 多租户由 service 层 {@code TenantHelper.ignore} + SQL 显式 {@code tenant_id} 控制（与 metric mapper 一致）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-ADMIN-003)
 */
public interface GzDashboardSnapshotMapper extends BaseMapperPlus<GzDashboardSnapshot, GzDashboardSnapshot> {

    /**
     * 查每个 metric 的最新一条快照（latest API）。
     *
     * <p>用 JOIN 子查询取各 metric_key 的 MAX(snapshot_time)，再回表取值。覆盖索引
     * {@code idx_metric_time (tenant_id, metric_key, snapshot_time DESC)} 命中。</p>
     *
     * @param tenantId 租户（V1.0 '1001'）
     * @return 各 metric 最新快照行（无快照则空列表）
     */
    @Select("""
        SELECT s.id, s.snapshot_time, s.metric_key, s.metric_value, s.tenant_id, s.del_flag
        FROM gz_dashboard_snapshot s
        INNER JOIN (
            SELECT metric_key, MAX(snapshot_time) AS max_time
            FROM gz_dashboard_snapshot
            WHERE tenant_id = #{tenantId} AND del_flag = '0'
            GROUP BY metric_key
        ) t ON s.metric_key = t.metric_key AND s.snapshot_time = t.max_time
        WHERE s.tenant_id = #{tenantId} AND s.del_flag = '0'
        """)
    List<GzDashboardSnapshot> selectLatestPerMetric(@Param("tenantId") String tenantId);

    /**
     * 查某 metric 最近 N 天快照序列（trend API，V1.0 留接口前端不展示）。
     *
     * @param tenantId  租户
     * @param metricKey 指标 key
     * @param since     起始时间（now - days）
     * @return 时间正序的趋势点
     */
    @Select("""
        SELECT snapshot_time, metric_value
        FROM gz_dashboard_snapshot
        WHERE tenant_id = #{tenantId} AND del_flag = '0'
          AND metric_key = #{metricKey} AND snapshot_time >= #{since}
        ORDER BY snapshot_time ASC
        """)
    List<GzDashboardTrendVO> selectTrend(@Param("tenantId") String tenantId,
                                         @Param("metricKey") String metricKey,
                                         @Param("since") LocalDateTime since);
}
