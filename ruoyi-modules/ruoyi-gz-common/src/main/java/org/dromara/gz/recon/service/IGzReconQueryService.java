package org.dromara.gz.recon.service;

import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.gz.recon.domain.excel.ReconExportRowVo;
import org.dromara.gz.recon.domain.vo.GzReconDailyVo;
import org.dromara.gz.recon.domain.vo.GzReconMonthlyVo;
import org.dromara.gz.recon.domain.vo.GzReconSettleVo;
import org.dromara.gz.recon.domain.vo.ReconSummaryVo;

import java.util.List;

/**
 * 对账中心 admin 查询 service（GZ-ADMIN-105）。
 *
 * <p>只读聚合 gz_recon_monthly / gz_recon_daily / gz_recon_settle（跑批已落表），admin 登录态执行
 * （ruoyi 拦截器自动按租户过滤）。Excel 明细查 gz_pay_transaction 源（每笔颗粒度，合同 §4.2.1）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-ADMIN-105)
 */
public interface IGzReconQueryService {

    /**
     * 月度汇总（四栏 + 分成）：某 business_type 在 [startMonth, endMonth] 内 SUM(gz_recon_monthly)。
     * 各月 settle/commission 已在跑批时 MAX0 + 取整，区间汇总直接相加（按月核算口径）。
     */
    ReconSummaryVo monthlySummary(String businessType, String startMonth, String endMonth);

    /**
     * 月度对账单明细列表（BizTable，按业务月倒序）。
     */
    List<GzReconMonthlyVo> monthlyList(String businessType, String startMonth, String endMonth);

    /**
     * 每日对账明细分页（按业务日倒序）。
     */
    TableDataInfo<GzReconDailyVo> dailyPage(String businessType, String startDate, String endDate, PageQuery pageQuery);

    /**
     * 季度结算列表（按季度倒序）。
     */
    List<GzReconSettleVo> settleList();

    /**
     * 构造 Excel 导出行（每笔交易颗粒度 + 末尾四项汇总行，合同 §4.2.1 / doc/11 F9.5）。
     * 金额 cent → 元字符串；不透视 / 不合并单元格 / 不做月度小计。
     */
    List<ReconExportRowVo> buildExportRows(String businessType, String startMonth, String endMonth);
}
