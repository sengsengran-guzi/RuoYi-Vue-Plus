package org.dromara.gz.recon.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.gz.recon.domain.entity.GzReconDaily;
import org.dromara.gz.recon.domain.entity.GzReconMonthly;
import org.dromara.gz.recon.domain.entity.GzReconSettle;
import org.dromara.gz.recon.domain.excel.ReconExportRowVo;
import org.dromara.gz.recon.domain.vo.GzReconDailyVo;
import org.dromara.gz.recon.domain.vo.GzReconMonthlyVo;
import org.dromara.gz.recon.domain.vo.GzReconSettleVo;
import org.dromara.gz.recon.domain.vo.ReconDetailRowVo;
import org.dromara.gz.recon.domain.vo.ReconSummaryVo;
import org.dromara.gz.recon.mapper.GzReconDailyMapper;
import org.dromara.gz.recon.mapper.GzReconMonthlyMapper;
import org.dromara.gz.recon.mapper.GzReconSettleMapper;
import org.dromara.gz.recon.mapper.GzReconSourceMapper;
import org.dromara.gz.recon.service.IGzReconQueryService;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

/**
 * 对账中心 admin 查询 service 实现（GZ-ADMIN-105）。
 *
 * @author kevin-coder (sensenran-guzi · GZ-ADMIN-105)
 */
@Service
@RequiredArgsConstructor
public class GzReconQueryServiceImpl implements IGzReconQueryService {

    private final GzReconDailyMapper dailyMapper;
    private final GzReconMonthlyMapper monthlyMapper;
    private final GzReconSettleMapper settleMapper;
    private final GzReconSourceMapper sourceMapper;

    @Override
    public ReconSummaryVo monthlySummary(String businessType, String startMonth, String endMonth) {
        List<GzReconMonthly> list = monthlyMapper.selectList(monthlyWrapper(businessType, startMonth, endMonth));
        ReconSummaryVo vo = new ReconSummaryVo();
        vo.setBusinessType(businessType);
        vo.setGmvCent(list.stream().mapToLong(m -> nz(m.getGmvCent())).sum());
        vo.setRefundCent(list.stream().mapToLong(m -> nz(m.getRefundCent())).sum());
        vo.setChannelFeeCent(list.stream().mapToLong(m -> nz(m.getChannelFeeCent())).sum());
        // 各月 settle 已在跑批时 MAX0，区间汇总直接相加（按月核算口径，doc/10 E5）
        vo.setSettleCent(list.stream().mapToLong(m -> nz(m.getSettleCent())).sum());
        vo.setCommissionCent(list.stream().mapToLong(m -> nz(m.getCommissionCent())).sum());
        vo.setCommissionRateBp(list.isEmpty() ? 400 : list.get(0).getCommissionRateBp());
        return vo;
    }

    @Override
    public List<GzReconMonthlyVo> monthlyList(String businessType, String startMonth, String endMonth) {
        LambdaQueryWrapper<GzReconMonthly> w = monthlyWrapper(businessType, startMonth, endMonth);
        w.orderByDesc(GzReconMonthly::getBusinessMonth).orderByAsc(GzReconMonthly::getBusinessType);
        return monthlyMapper.selectVoList(w);
    }

    @Override
    public TableDataInfo<GzReconDailyVo> dailyPage(String businessType, String startDate, String endDate, PageQuery pageQuery) {
        LambdaQueryWrapper<GzReconDaily> w = Wrappers.<GzReconDaily>lambdaQuery()
            .eq(StrUtil.isNotBlank(businessType), GzReconDaily::getBusinessType, businessType)
            .ge(StrUtil.isNotBlank(startDate), GzReconDaily::getBusinessDay, parseDate(startDate))
            .le(StrUtil.isNotBlank(endDate), GzReconDaily::getBusinessDay, parseDate(endDate))
            .orderByDesc(GzReconDaily::getBusinessDay)
            .orderByAsc(GzReconDaily::getBusinessType);
        Page<GzReconDailyVo> page = dailyMapper.selectVoPage(pageQuery.build(), w);
        return TableDataInfo.build(page);
    }

    @Override
    public List<GzReconSettleVo> settleList() {
        return settleMapper.selectVoList(Wrappers.<GzReconSettle>lambdaQuery().orderByDesc(GzReconSettle::getQuarter));
    }

    @Override
    public List<ReconExportRowVo> buildExportRows(String businessType, String startMonth, String endMonth) {
        LocalDate startDate = YearMonth.parse(startMonth).atDay(1);
        LocalDate endDate = YearMonth.parse(endMonth).atEndOfMonth();
        List<ReconDetailRowVo> details = sourceMapper.selectDetailRows(businessType, startDate, endDate);

        List<ReconExportRowVo> rows = new ArrayList<>(details.size() + 1);
        for (ReconDetailRowVo d : details) {
            ReconExportRowVo r = new ReconExportRowVo();
            r.setOutTradeNo(d.getOutTradeNo());
            r.setBusinessOrderNo(d.getBusinessOrderNo());
            r.setAmountYuan(yuan(d.getAmountCent()));
            r.setPaidTimeText(d.getPaidTime() == null ? "" : d.getPaidTime().toString());
            r.setStatusText(statusText(d));
            r.setFeeYuan(yuan(d.getFeeCent()));
            r.setRefundYuan(d.getRefundAmountCent() == null ? "" : yuan(d.getRefundAmountCent()));
            r.setRefundedTimeText(d.getRefundedTime() == null ? "" : d.getRefundedTime().toString());
            rows.add(r);
        }

        // 末尾四项汇总行（合同 §4.2.1）。D16 P10：汇总【直接取 gz_recon_monthly 已落库值】而非从 detail 现算 ——
        //   monthly 跑批按 refunded_time 当月归属退款（C4），detail 按 paid_time 取交易行 + 无条件 LEFT JOIN 退款，
        //   跨月退款（如 5 月成交 6 月退款）两口径会打架；导出汇总与对账中心月度 settle/commission 必须同源，
        //   否则甲方核季度结算时两份凭证对不上。明细行 refund 仍按交易颗粒度展示作凭证。
        ReconSummaryVo monthly = monthlySummary(businessType, startMonth, endMonth);
        ReconExportRowVo total = new ReconExportRowVo();
        total.setOutTradeNo("【本期汇总·与对账中心同源】");
        total.setBusinessOrderNo("");
        total.setAmountYuan(yuan(monthly.getGmvCent()));
        total.setPaidTimeText("");
        total.setStatusText("实际到账 " + yuan(monthly.getSettleCent()) + " 元");
        total.setFeeYuan(yuan(monthly.getChannelFeeCent()));
        total.setRefundYuan(yuan(monthly.getRefundCent()));
        total.setRefundedTimeText("");
        rows.add(total);
        return rows;
    }

    // --------------------------------------------------------------------

    private LambdaQueryWrapper<GzReconMonthly> monthlyWrapper(String businessType, String startMonth, String endMonth) {
        // business_month 为 VARCHAR(7) yyyy-MM，字典序 = 时间序，可直接 ge/le 比较
        return Wrappers.<GzReconMonthly>lambdaQuery()
            .eq(StrUtil.isNotBlank(businessType), GzReconMonthly::getBusinessType, businessType)
            .ge(StrUtil.isNotBlank(startMonth), GzReconMonthly::getBusinessMonth, startMonth)
            .le(StrUtil.isNotBlank(endMonth), GzReconMonthly::getBusinessMonth, endMonth);
    }

    private static String statusText(ReconDetailRowVo d) {
        boolean refunded = d.getRefundAmountCent() != null && d.getRefundedTime() != null;
        return refunded ? "已退款" : "已支付";
    }

    /** 分 → 元（精确，BigDecimal 不丢精度）。 */
    private static String yuan(Long cent) {
        return BigDecimal.valueOf(nz(cent)).movePointLeft(2).toPlainString();
    }

    private static LocalDate parseDate(String s) {
        return StrUtil.isBlank(s) ? null : LocalDate.parse(s);
    }

    private static long nz(Long v) {
        return v == null ? 0L : v;
    }
}
