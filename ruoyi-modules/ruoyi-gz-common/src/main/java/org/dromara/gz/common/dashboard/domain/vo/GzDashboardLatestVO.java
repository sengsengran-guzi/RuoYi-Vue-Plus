package org.dromara.gz.common.dashboard.domain.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 看板最新快照 VO（GET /system/gz/dashboard/latest 返回）。
 *
 * <p>{@code snapshotTime} 为本批快照统一时间（前端据此算「更新于 X 分钟前」）；{@code metrics} 为 5 个指标的最新值。
 * 若快照表为空（cron 尚未跑过 / 首次部署）→ snapshotTime=null + metrics 空列表，前端显示「-」。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-ADMIN-003)
 */
@Data
public class GzDashboardLatestVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 本批快照时间（5 个 metric 取各自最新 snapshot_time，正常同批一致；取最大值兜底） */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime snapshotTime;

    /** 5 个指标最新值 */
    private List<MetricItem> metrics;

    /**
     * 单个指标项。
     */
    @Data
    public static class MetricItem implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        /** 指标 key（total_users / today_new_users / ...） */
        private String metricKey;

        /** 卡片标题（中文默认；前端可按 metricKey 走 i18n 覆盖） */
        private String title;

        /** 指标数值（前端千分位格式化） */
        private Long value;
    }
}
