package org.dromara.gz.common.dashboard.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.dromara.common.tenant.core.TenantEntity;

import java.io.Serial;
import java.time.LocalDateTime;

/**
 * gz_dashboard_snapshot — 数据看板快照 entity（GZ-ADMIN-003）。
 *
 * <p>wide-long 形式（决策 D1）：每个 metric 一行；同一批次 5 个 metric 共用同一 {@code snapshotTime}。
 * 写入由 SnailJob cron（{@code gzDashboardSnapshotTask}）每 5 min 集中产出，前端只读本表不直接 COUNT 源表。</p>
 *
 * <p>字段口径权威：ticket GZ-ADMIN-003 AC 1 + doc/11 §6/§7 缺口段（DDL 落地后由复盘补）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-ADMIN-003)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("gz_dashboard_snapshot")
public class GzDashboardSnapshot extends TenantEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键（DB AUTO_INCREMENT） */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 快照时间（5 min 粒度，同批次 5 行共用同一 now()） */
    private LocalDateTime snapshotTime;

    /**
     * 指标 key：total_users / today_new_users / total_bookings / today_bookings / total_news_reads
     * （取值常量见 {@link org.dromara.gz.common.dashboard.enums.DashboardMetric}）。
     */
    private String metricKey;

    /** 指标数值 */
    private Long metricValue;

}
