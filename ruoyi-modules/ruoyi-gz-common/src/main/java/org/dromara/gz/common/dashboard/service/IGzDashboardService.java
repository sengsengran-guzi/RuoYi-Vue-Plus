package org.dromara.gz.common.dashboard.service;

import org.dromara.gz.common.dashboard.domain.vo.GzDashboardLatestVO;
import org.dromara.gz.common.dashboard.domain.vo.GzDashboardTrendVO;

import java.util.List;

/**
 * 数据看板 service（GZ-ADMIN-003）。
 *
 * <p>核心可测逻辑全在本 service —— SnailJob 执行器 {@code GzDashboardSnapshotJob} 仅触发壳，
 * 单测脱离 SnailJob 直接测 {@link #takeSnapshot()}（AC 7）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-ADMIN-003)
 */
public interface IGzDashboardService {

    /**
     * 快照结果（写入统计，回写 SnailJob 执行结果消息用）。
     *
     * @param metrics       本批计算的指标个数
     * @param insertedRows  实际落库行数（成功视 == metrics）
     * @param success       是否全部成功
     * @param message       说明（含异常信息）
     */
    record SnapshotResult(int metrics, int insertedRows, boolean success, String message) {
    }

    /**
     * 计算 5 个 metric 并批量写入一批快照（同一 snapshot_time）。
     *
     * <p>cron（每 5 min）+ 手动刷新（refresh API）共用入口。异常不外抛 —— 内部 catch 后返回
     * {@code success=false} 的 result（不让 cron 阻塞 / 不让 refresh 抛 500，AC 7 异常处理）。</p>
     *
     * @return 快照结果
     */
    SnapshotResult takeSnapshot();

    /**
     * 查 5 个 metric 最新值 + 快照时间（latest API）。
     *
     * @return 最新看板数据（无快照则 snapshotTime=null + 空 metrics）
     */
    GzDashboardLatestVO getLatest();

    /**
     * 查某 metric 最近 N 天趋势（trend API，V1.0 留接口前端不展示）。
     *
     * @param metricKey 指标 key
     * @param days      天数（默认 7）
     * @return 时间正序趋势点
     */
    List<GzDashboardTrendVO> getTrend(String metricKey, int days);
}
