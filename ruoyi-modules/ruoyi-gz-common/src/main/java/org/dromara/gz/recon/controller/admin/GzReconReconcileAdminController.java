package org.dromara.gz.recon.controller.admin;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.R;
import org.dromara.common.excel.utils.ExcelUtil;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.common.web.core.BaseController;
import org.dromara.gz.recon.domain.excel.ReconExportRowVo;
import org.dromara.gz.recon.domain.vo.GzReconDailyVo;
import org.dromara.gz.recon.domain.vo.GzReconMonthlyVo;
import org.dromara.gz.recon.domain.vo.GzReconSettleVo;
import org.dromara.gz.recon.domain.vo.ReconSummaryVo;
import org.dromara.gz.recon.service.IGzReconBatchService;
import org.dromara.gz.recon.service.IGzReconQueryService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * GZ-ADMIN-105 ⭐ 对账中心（admin 端 / 合同 §4.1 4% 分成兑现核心）。
 *
 * <p>路径前缀 {@code /system/gz/recon}（对齐既有 gz admin 控制器约定，与 ruoyi 自带 /system/* 域隔离）。
 * 跑批已落表（gz_recon_daily/monthly/settle），本控制器只读聚合；Excel 导出查 gz_pay_transaction
 * 源（每笔颗粒度，合同 §4.2.1）。金额全 cent（前端 / 100 显示元）。</p>
 *
 * <p>权限（DDL menu 11004/11041/11005，财务敏感仅 owner 授权）：</p>
 * <ul>
 *   <li>{@code gz:recon:reconcile:list} — 对账中心查询（summary / monthly / daily）</li>
 *   <li>{@code gz:recon:reconcile:export} — Excel 导出（合同 §4.2.1）</li>
 *   <li>{@code gz:recon:settle:list} — 季度结算列表</li>
 * </ul>
 *
 * @author kevin-coder (sensenran-guzi · GZ-ADMIN-105)
 */
@Slf4j
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/system/gz/recon")
public class GzReconReconcileAdminController extends BaseController {

    private final IGzReconQueryService queryService;
    private final IGzReconBatchService batchService;

    /**
     * 月度汇总（四栏数字卡片 + 分成）：某 business_type 在 [startMonth, endMonth] SUM(gz_recon_monthly)。
     *
     * @param businessType 业务线 preorder（A）/ gacha（B）
     * @param startMonth   起始月 yyyy-MM
     * @param endMonth     截止月 yyyy-MM
     */
    @SaCheckPermission("gz:recon:reconcile:list")
    @GetMapping("/reconcile/summary")
    public R<ReconSummaryVo> summary(@NotBlank @RequestParam("businessType") String businessType,
                                     @NotBlank @RequestParam("startMonth") String startMonth,
                                     @NotBlank @RequestParam("endMonth") String endMonth) {
        return R.ok(queryService.monthlySummary(businessType, startMonth, endMonth));
    }

    /**
     * 月度对账单明细列表（BizTable，按业务月倒序）。
     */
    @SaCheckPermission("gz:recon:reconcile:list")
    @GetMapping("/reconcile/monthly")
    public R<List<GzReconMonthlyVo>> monthly(@RequestParam(value = "businessType", required = false) String businessType,
                                             @RequestParam(value = "startMonth", required = false) String startMonth,
                                             @RequestParam(value = "endMonth", required = false) String endMonth) {
        return R.ok(queryService.monthlyList(businessType, startMonth, endMonth));
    }

    /**
     * 每日对账明细分页（按业务日倒序）。pageNum / pageSize 由 PageQuery 自动绑定。
     */
    @SaCheckPermission("gz:recon:reconcile:list")
    @GetMapping("/reconcile/daily")
    public TableDataInfo<GzReconDailyVo> daily(@RequestParam(value = "businessType", required = false) String businessType,
                                               @RequestParam(value = "startDate", required = false) String startDate,
                                               @RequestParam(value = "endDate", required = false) String endDate,
                                               PageQuery pageQuery) {
        return queryService.dailyPage(businessType, startDate, endDate, pageQuery);
    }

    /**
     * 季度结算列表（季度 / 分成合计 / 月维护费 / 应付合计 / 状态，按季度倒序）。
     */
    @SaCheckPermission("gz:recon:settle:list")
    @GetMapping("/settle/list")
    public R<List<GzReconSettleVo>> settleList() {
        return R.ok(queryService.settleList());
    }

    /**
     * Excel 导出（合同 §4.2.1 强制）：某业务线某月份区间每笔交易颗粒度 + 末尾四项汇总行。
     * <p>ruoyi 自带 EasyExcel 流式写；写 sys_oper_log（@Log EXPORT）。不透视 / 不合并单元格。</p>
     *
     * @param businessType 业务线 preorder / gacha
     * @param startMonth   起始月 yyyy-MM
     * @param endMonth     截止月 yyyy-MM
     */
    @Log(title = "对账中心导出", businessType = BusinessType.EXPORT)
    @SaCheckPermission("gz:recon:reconcile:export")
    @PostMapping("/reconcile/export")
    public void export(@NotBlank @RequestParam("businessType") String businessType,
                       @NotBlank @RequestParam("startMonth") String startMonth,
                       @NotBlank @RequestParam("endMonth") String endMonth,
                       HttpServletResponse response) {
        List<ReconExportRowVo> rows = queryService.buildExportRows(businessType, startMonth, endMonth);
        String sheet = "对账明细_" + ("preorder".equals(businessType) ? "业务线A" : "业务线B") + "_" + startMonth + "至" + endMonth;
        ExcelUtil.exportExcel(rows, sheet, ReconExportRowVo.class, response);
    }

    /**
     * 立即重算对账（D16 #1，方案 A）：owner 手动触发跑批，<b>消除「忘了在 SnailJob 控制台注册 cron →
     * 首月看分成全 ¥0」</b> 的部署风险——不依赖定时也能当场出数。
     *
     * <p>跑批 service UPSERT 幂等（重跑安全）。默认重算<b>前一日</b>（cron 同口径）+ 其所属月度；
     * 可传 {@code businessDay=yyyy-MM-dd} / {@code month=yyyy-MM} 指定。财务敏感，复用 export 权限（仅 owner）。</p>
     */
    @Log(title = "对账立即重算", businessType = BusinessType.OTHER)
    @SaCheckPermission("gz:recon:reconcile:export")
    @PostMapping("/reconcile/rebuild")
    public R<Void> rebuild(@RequestParam(value = "businessDay", required = false) String businessDay,
                           @RequestParam(value = "month", required = false) String month) {
        java.time.LocalDate day = (businessDay == null || businessDay.isBlank())
            ? java.time.LocalDate.now().minusDays(1) : java.time.LocalDate.parse(businessDay);
        int dailyRows = batchService.runDaily(day);
        java.time.YearMonth ym = (month == null || month.isBlank())
            ? java.time.YearMonth.from(day) : java.time.YearMonth.parse(month);
        int monthlyRows = batchService.runMonthly(ym);
        log.info("[GZ-RECON] owner 手动重算 day={} month={} dailyRows={} monthlyRows={}", day, ym, dailyRows, monthlyRows);
        return R.ok();
    }
}
