package org.dromara.gz.common.dashboard.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.R;
import org.dromara.common.ratelimiter.annotation.RateLimiter;
import org.dromara.common.ratelimiter.enums.LimitType;
import org.dromara.common.web.core.BaseController;
import org.dromara.gz.common.dashboard.domain.vo.GzDashboardLatestVO;
import org.dromara.gz.common.dashboard.domain.vo.GzDashboardTrendVO;
import org.dromara.gz.common.dashboard.service.IGzDashboardService;
import org.dromara.gz.common.dashboard.service.IGzDashboardService.SnapshotResult;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * GZ-ADMIN-003 数据看板（admin 端）。
 *
 * <p>路径前缀 {@code /system/gz/dashboard} —— 与 ruoyi 自带 {@code /system/...} 域名隔离。
 * 前端永远读 snapshot 表（latest / trend 查 gz_dashboard_snapshot），不直接 COUNT 源表（性能 + 锁竞争）。</p>
 *
 * <p>权限（DDL menu_id 300/301）：</p>
 * <ul>
 *   <li>{@code gz:dashboard:view} — 查看看板（owner + staff）</li>
 *   <li>{@code gz:dashboard:refresh} — 手动刷新（owner only，+ IP 限流 5s）</li>
 * </ul>
 *
 * @author kevin-coder (sensenran-guzi · GZ-ADMIN-003)
 */
@Slf4j
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/system/gz/dashboard")
public class GzDashboardController extends BaseController {

    private final IGzDashboardService dashboardService;

    /**
     * 看板最新快照（5 个 metric 最新值 + 快照时间）。
     */
    @SaCheckPermission("gz:dashboard:view")
    @GetMapping("/latest")
    public R<GzDashboardLatestVO> latest() {
        return R.ok(dashboardService.getLatest());
    }

    /**
     * 某 metric 最近 N 天趋势（图表数据源，V1.0 前端不展示，API 留接口 V1.1 用）。
     *
     * @param metricKey 指标 key（total_users / today_new_users / ...）
     * @param days      天数（默认 7）
     */
    @SaCheckPermission("gz:dashboard:view")
    @GetMapping("/trend")
    public R<List<GzDashboardTrendVO>> trend(@NotBlank @RequestParam("metricKey") String metricKey,
                                             @RequestParam(value = "days", defaultValue = "7") int days) {
        return R.ok(dashboardService.getTrend(metricKey, days));
    }

    /**
     * 立即刷新：同步触发一次快照计算（owner 权限 + IP 限流 5s，防误点并发触发，强约束 #5）。
     *
     * <p>service.takeSnapshot 内部已 catch 异常不外抛 → 即便底层 SQL 异常也返回 R.fail 而非 500。</p>
     */
    @SaCheckPermission("gz:dashboard:refresh")
    @RateLimiter(count = 1, time = 5, limitType = LimitType.IP, message = "{rate.limiter.message}")
    @PostMapping("/refresh")
    public R<Void> refresh() {
        SnapshotResult result = dashboardService.takeSnapshot();
        if (result.success()) {
            return R.ok("刷新成功");
        }
        return R.fail(result.message());
    }
}
