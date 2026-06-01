package org.dromara.gz.common.dashboard.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.tenant.helper.TenantHelper;
import org.dromara.gz.common.dashboard.domain.entity.GzDashboardSnapshot;
import org.dromara.gz.common.dashboard.domain.vo.GzDashboardLatestVO;
import org.dromara.gz.common.dashboard.domain.vo.GzDashboardTrendVO;
import org.dromara.gz.common.dashboard.enums.DashboardMetric;
import org.dromara.gz.common.dashboard.mapper.GzDashboardMetricMapper;
import org.dromara.gz.common.dashboard.mapper.GzDashboardSnapshotMapper;
import org.dromara.gz.common.dashboard.service.IGzDashboardService;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.ToLongFunction;
import java.util.stream.Collectors;

/**
 * 数据看板 service 实现（GZ-ADMIN-003）。
 *
 * <p>所有 DB 操作用 {@code TenantHelper.ignore} 包裹（cron / refresh 无登录态租户；V1.0 固定 '1001'，
 * mapper SQL 显式带 tenant 参数）。前端永远读 snapshot 表，本类 takeSnapshot 是唯一写快照入口。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-ADMIN-003)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GzDashboardServiceImpl implements IGzDashboardService {

    /** V1.0 单租户固定 '1001'（cron 上下文无登录态，CLAUDE.md §6 #2）。 */
    private static final String TENANT_ID = "1001";

    private final GzDashboardMetricMapper metricMapper;
    private final GzDashboardSnapshotMapper snapshotMapper;

    @Override
    public SnapshotResult takeSnapshot() {
        return TenantHelper.ignore(() -> {
            try {
                LocalDateTime now = LocalDateTime.now();

                // 5 个 metric 计算（key → 取数函数，保证与 DashboardMetric 枚举一一对应）
                Map<DashboardMetric, ToLongFunction<String>> calculators = Map.of(
                    DashboardMetric.TOTAL_USERS, metricMapper::countTotalUsers,
                    DashboardMetric.TODAY_NEW_USERS, metricMapper::countTodayNewUsers,
                    DashboardMetric.TOTAL_BOOKINGS, metricMapper::countTotalBookings,
                    DashboardMetric.TODAY_BOOKINGS, metricMapper::countTodayBookings,
                    DashboardMetric.TOTAL_NEWS_READS, metricMapper::sumTotalNewsReads
                );

                List<GzDashboardSnapshot> batch = new ArrayList<>(DashboardMetric.all().size());
                for (DashboardMetric metric : DashboardMetric.all()) {
                    long value = calculators.get(metric).applyAsLong(TENANT_ID);
                    GzDashboardSnapshot row = GzDashboardSnapshot.builder()
                        .snapshotTime(now)
                        .metricKey(metric.getMetricKey())
                        .metricValue(value)
                        .build();
                    // cron / ignore 上下文显式落租户（拦截器被 ignore 不再自动填）
                    row.setTenantId(TENANT_ID);
                    batch.add(row);
                }

                snapshotMapper.insertBatch(batch);
                String msg = String.format("看板快照写入完成：snapshot_time=%s / metrics=%d", now, batch.size());
                log.info("[GZ-DASHBOARD-SNAPSHOT] {}", msg);
                return new SnapshotResult(batch.size(), batch.size(), true, msg);
            } catch (Exception ex) {
                // 不外抛：cron 不阻塞 / refresh 不抛 500（AC 7 异常处理）。记录后返回 failure。
                log.error("[GZ-DASHBOARD-SNAPSHOT] 快照计算失败", ex);
                return new SnapshotResult(DashboardMetric.all().size(), 0, false,
                    "看板快照计算失败：" + ex.getMessage());
            }
        });
    }

    @Override
    public GzDashboardLatestVO getLatest() {
        return TenantHelper.ignore(() -> {
            List<GzDashboardSnapshot> latest = snapshotMapper.selectLatestPerMetric(TENANT_ID);

            GzDashboardLatestVO vo = new GzDashboardLatestVO();

            // metric_key → 最新值（用于按枚举顺序组装，保证前端永远拿到 5 张卡，缺失补 0）
            Map<String, Long> valueByKey = latest.stream()
                .collect(Collectors.toMap(GzDashboardSnapshot::getMetricKey,
                    GzDashboardSnapshot::getMetricValue, (a, b) -> a));

            // 快照时间取本批最大值（正常 5 行同批一致；容错取 max）
            LocalDateTime snapshotTime = latest.stream()
                .map(GzDashboardSnapshot::getSnapshotTime)
                .filter(java.util.Objects::nonNull)
                .max(LocalDateTime::compareTo)
                .orElse(null);
            vo.setSnapshotTime(snapshotTime);

            List<GzDashboardLatestVO.MetricItem> items = new ArrayList<>(DashboardMetric.all().size());
            for (DashboardMetric metric : DashboardMetric.all()) {
                GzDashboardLatestVO.MetricItem item = new GzDashboardLatestVO.MetricItem();
                item.setMetricKey(metric.getMetricKey());
                item.setTitle(metric.getTitle());
                // 无快照（cron 未跑过）→ value=null，前端显示「-」
                item.setValue(valueByKey.get(metric.getMetricKey()));
                items.add(item);
            }
            vo.setMetrics(items);
            return vo;
        });
    }

    @Override
    public List<GzDashboardTrendVO> getTrend(String metricKey, int days) {
        int safeDays = days <= 0 ? 7 : days;
        LocalDateTime since = LocalDateTime.now().minusDays(safeDays);
        return TenantHelper.ignore(() -> snapshotMapper.selectTrend(TENANT_ID, metricKey, since));
    }
}
