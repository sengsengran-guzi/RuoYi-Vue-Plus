package org.dromara.gz.common.dashboard.service;

import org.dromara.gz.common.dashboard.domain.entity.GzDashboardSnapshot;
import org.dromara.gz.common.dashboard.domain.vo.GzDashboardLatestVO;
import org.dromara.gz.common.dashboard.enums.DashboardMetric;
import org.dromara.gz.common.dashboard.mapper.GzDashboardMetricMapper;
import org.dromara.gz.common.dashboard.mapper.GzDashboardSnapshotMapper;
import org.dromara.gz.common.dashboard.service.IGzDashboardService.SnapshotResult;
import org.dromara.gz.common.dashboard.service.impl.GzDashboardServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * {@link GzDashboardServiceImpl} 单测（GZ-ADMIN-003 AC 7，全 mock，脱离 SnailJob + DB）。
 *
 * <p>覆盖：</p>
 * <ol>
 *   <li>takeSnapshot happy path — mock 5 个 COUNT → 批量 insert 5 行（key/value 正确，同一 snapshot_time）</li>
 *   <li>takeSnapshot 异常处理 — mock metric mapper 抛异常 → 不外抛，返回 success=false</li>
 *   <li>getLatest — 部分 metric 有快照 → 仍组装 5 张卡，缺失补 null（前端显示「-」）</li>
 * </ol>
 *
 * <p>{@code TenantHelper.ignore} 内部仅操作 mybatis-plus InterceptorIgnoreHelper 的 ThreadLocal，
 * 不依赖 spring 上下文，单测直接执行 supplier，无需 mockStatic。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-ADMIN-003)
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class GzDashboardServiceImplTest {

    @Mock
    private GzDashboardMetricMapper metricMapper;

    @Mock
    private GzDashboardSnapshotMapper snapshotMapper;

    private GzDashboardServiceImpl service;

    private GzDashboardServiceImpl service() {
        if (service == null) {
            service = new GzDashboardServiceImpl(metricMapper, snapshotMapper);
        }
        return service;
    }

    // ============================================================
    //  1. takeSnapshot happy path：5 个 COUNT → 批量 insert 5 行
    // ============================================================

    @Test
    @DisplayName("takeSnapshot happy：mock 5 COUNT → batch insert 5 行（key/value 正确，同批同 time）")
    void takeSnapshot_happyPath_insert5Rows() {
        when(metricMapper.countTotalUsers(eq("1001"))).thenReturn(120L);
        when(metricMapper.countTodayNewUsers(eq("1001"))).thenReturn(8L);
        when(metricMapper.countTotalBookings(eq("1001"))).thenReturn(45L);
        when(metricMapper.countTodayBookings(eq("1001"))).thenReturn(3L);
        when(metricMapper.sumTotalNewsReads(eq("1001"))).thenReturn(9870L);
        when(snapshotMapper.insertBatch(anyList())).thenReturn(true);

        SnapshotResult result = service().takeSnapshot();

        assertTrue(result.success());
        assertEquals(5, result.metrics());
        assertEquals(5, result.insertedRows());

        // 捕获 insertBatch 的 batch，校验 5 行 + key/value 映射 + 同一 snapshot_time + tenant_id
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<GzDashboardSnapshot>> captor = ArgumentCaptor.forClass(List.class);
        verify(snapshotMapper).insertBatch(captor.capture());
        List<GzDashboardSnapshot> batch = captor.getValue();
        assertEquals(5, batch.size());

        Map<String, Long> valueByKey = batch.stream()
            .collect(Collectors.toMap(GzDashboardSnapshot::getMetricKey, GzDashboardSnapshot::getMetricValue));
        assertEquals(120L, valueByKey.get(DashboardMetric.TOTAL_USERS.getMetricKey()));
        assertEquals(8L, valueByKey.get(DashboardMetric.TODAY_NEW_USERS.getMetricKey()));
        assertEquals(45L, valueByKey.get(DashboardMetric.TOTAL_BOOKINGS.getMetricKey()));
        assertEquals(3L, valueByKey.get(DashboardMetric.TODAY_BOOKINGS.getMetricKey()));
        assertEquals(9870L, valueByKey.get(DashboardMetric.TOTAL_NEWS_READS.getMetricKey()));

        // 同一批共用同一 snapshot_time
        long distinctTimes = batch.stream().map(GzDashboardSnapshot::getSnapshotTime).distinct().count();
        assertEquals(1, distinctTimes);
        // 全部落 tenant '1001'
        assertTrue(batch.stream().allMatch(s -> "1001".equals(s.getTenantId())));
    }

    // ============================================================
    //  2. takeSnapshot 异常处理：mapper 抛异常 → 不外抛，返回 failure
    // ============================================================

    @Test
    @DisplayName("takeSnapshot 异常：COUNT mapper 抛异常 → 不阻塞/不外抛，返回 success=false + 不 insert")
    void takeSnapshot_mapperThrows_returnsFailure_noInsert() {
        when(metricMapper.countTotalUsers(anyString()))
            .thenThrow(new RuntimeException("simulated SQL error"));

        // 关键：不应抛异常出来（cron 不阻塞 / refresh 不 500）
        SnapshotResult result = assertDoesNotThrow(() -> service().takeSnapshot());

        assertFalse(result.success());
        assertEquals(0, result.insertedRows());
        assertTrue(result.message().contains("看板快照计算失败"));
        // 计算阶段就失败 → 不应写库
        verify(snapshotMapper, never()).insertBatch(anyList());
    }

    // ============================================================
    //  3. getLatest：部分 metric 有快照 → 仍组装 5 张卡，缺失补 null
    // ============================================================

    @Test
    @DisplayName("getLatest：部分 metric 有快照 → 输出 5 张卡（缺失 value=null），snapshotTime 取 max")
    void getLatest_partialMetrics_padsToFiveCards() {
        LocalDateTime t = LocalDateTime.of(2026, 6, 4, 11, 5, 0);
        // 只有 3 个 metric 有快照（模拟 cron 跑过但某些源表为空被聚合略过的极端情况）
        GzDashboardSnapshot s1 = GzDashboardSnapshot.builder()
            .snapshotTime(t).metricKey(DashboardMetric.TOTAL_USERS.getMetricKey()).metricValue(120L).build();
        GzDashboardSnapshot s2 = GzDashboardSnapshot.builder()
            .snapshotTime(t).metricKey(DashboardMetric.TOTAL_BOOKINGS.getMetricKey()).metricValue(45L).build();
        GzDashboardSnapshot s3 = GzDashboardSnapshot.builder()
            .snapshotTime(t).metricKey(DashboardMetric.TOTAL_NEWS_READS.getMetricKey()).metricValue(9870L).build();
        when(snapshotMapper.selectLatestPerMetric(eq("1001"))).thenReturn(List.of(s1, s2, s3));

        GzDashboardLatestVO vo = service().getLatest();

        assertEquals(t, vo.getSnapshotTime());
        assertEquals(5, vo.getMetrics().size(), "前端永远拿到 5 张卡");

        Map<String, Long> valueByKey = vo.getMetrics().stream()
            .collect(java.util.HashMap::new,
                (m, item) -> m.put(item.getMetricKey(), item.getValue()), java.util.HashMap::putAll);
        assertEquals(120L, valueByKey.get(DashboardMetric.TOTAL_USERS.getMetricKey()));
        assertEquals(45L, valueByKey.get(DashboardMetric.TOTAL_BOOKINGS.getMetricKey()));
        assertEquals(9870L, valueByKey.get(DashboardMetric.TOTAL_NEWS_READS.getMetricKey()));
        // 缺失的两个 → null（前端显示「-」）
        assertNull(valueByKey.get(DashboardMetric.TODAY_NEW_USERS.getMetricKey()));
        assertNull(valueByKey.get(DashboardMetric.TODAY_BOOKINGS.getMetricKey()));
    }

    // ============================================================
    //  4. getLatest：空快照（cron 未跑过）→ snapshotTime=null + 5 张卡全 null
    // ============================================================

    @Test
    @DisplayName("getLatest 空快照：cron 未跑过 → snapshotTime=null + 5 卡 value 全 null")
    void getLatest_noSnapshot_returnsNullTimeAndEmptyValues() {
        when(snapshotMapper.selectLatestPerMetric(eq("1001"))).thenReturn(List.of());

        GzDashboardLatestVO vo = service().getLatest();

        assertNull(vo.getSnapshotTime());
        assertEquals(5, vo.getMetrics().size());
        assertTrue(vo.getMetrics().stream().allMatch(m -> m.getValue() == null));
    }
}
