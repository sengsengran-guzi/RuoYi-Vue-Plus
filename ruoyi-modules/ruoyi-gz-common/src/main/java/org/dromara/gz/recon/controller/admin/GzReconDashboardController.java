package org.dromara.gz.recon.controller.admin;

import cn.dev33.satoken.annotation.SaCheckPermission;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.R;
import org.dromara.common.web.core.BaseController;
import org.dromara.gz.recon.domain.vo.GzDashboardV11SummaryVO;
import org.dromara.gz.recon.service.IGzReconDashboardService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * GZ-ADMIN-106 数据看板 V1.1 交易盘面（admin 端，单聚合接口）。
 *
 * <p>路径 {@code /system/gz/recon/dashboard}（与 ADMIN-105 同 recon 域）。一次性返所有 V1.1 卡片，
 * 前端 1 次调用本地分发（不前端多次串行）。owner-only 权限 {@code gz:recon:dashboard:v11}
 * （财务敏感，DDL 在 dashboard 菜单下挂按钮权限授 owner）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-ADMIN-106)
 */
@Slf4j
@RequiredArgsConstructor
@RestController
@RequestMapping("/system/gz/recon/dashboard")
public class GzReconDashboardController extends BaseController {

    private final IGzReconDashboardService dashboardService;

    /**
     * V1.1 交易盘面单聚合接口（今日订单/GMV + 本月 GMV/退款/实际到账 + 扭蛋开盒 + 待发货 + 热销 Top10）。
     */
    @SaCheckPermission("gz:recon:dashboard:v11")
    @GetMapping("/v11-summary")
    public R<GzDashboardV11SummaryVO> v11Summary() {
        return R.ok(dashboardService.getV11Summary());
    }
}
