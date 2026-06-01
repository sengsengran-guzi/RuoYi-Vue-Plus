package org.dromara.gz.common.dashboard.domain.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 看板趋势点 VO（GET /system/gz/dashboard/trend 返回列表元素）。
 *
 * <p>V1.0 API 留接口前端不展示（图表选型 echarts 推 V1.1，决策 D4）；返回某 metric 最近 N 天每 5 min 的快照序列。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-ADMIN-003)
 */
@Data
public class GzDashboardTrendVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 快照时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime snapshotTime;

    /** 该时刻指标值 */
    private Long metricValue;
}
