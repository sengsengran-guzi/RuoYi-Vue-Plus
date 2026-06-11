package org.dromara.gz.recon.service.impl;

import cn.hutool.core.util.StrUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.service.ConfigService;
import org.dromara.common.tenant.helper.TenantHelper;
import org.dromara.gz.common.pay.enums.PayBusinessType;
import org.dromara.gz.recon.domain.entity.GzReconDaily;
import org.dromara.gz.recon.domain.entity.GzReconMonthly;
import org.dromara.gz.recon.domain.entity.GzReconSettle;
import org.dromara.gz.recon.domain.vo.ReconSummaryVo;
import org.dromara.gz.recon.mapper.GzReconDailyMapper;
import org.dromara.gz.recon.mapper.GzReconMonthlyMapper;
import org.dromara.gz.recon.mapper.GzReconSettleMapper;
import org.dromara.gz.recon.mapper.GzReconSourceMapper;
import org.dromara.gz.recon.service.IGzReconBatchService;
import org.dromara.gz.recon.service.internal.ReconCalculator;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

/**
 * 对账跑批 service 实现（GZ-ADMIN-105）。
 *
 * <p>合同 §4.1 4% 分成兑现的零容忍计算。所有跑批在 {@link TenantHelper#ignore} 下执行（cron 无登录态，
 * V1.1 单租户固定 '1001'，mapper UPSERT 显式带 tenant_id）。业务线分流统一走 {@code business_type}
 * （preorder=A / gacha=B，test/pindou 不进对账，doc/11 F4.2 / A.9）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-ADMIN-105)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GzReconBatchServiceImpl implements IGzReconBatchService {

    /** V1.1 单租户固定 '1001'（cron 无登录态，CLAUDE.md §6 #2）。 */
    private static final String TENANT_ID = "1001";

    /** 进对账的两条业务线（A=preorder / B=gacha，不交叉冲抵）。 */
    private static final List<String> RECON_BUSINESS_TYPES = List.of(PayBusinessType.PREORDER, PayBusinessType.GACHA);

    private static final String KEY_RATE_PREORDER = "gz.commission.rate.preorder";
    private static final String KEY_RATE_GACHA = "gz.commission.rate.gacha";
    private static final String KEY_MAINTENANCE_MONTHLY = "gz.commission.maintenance.monthly.cent";

    /** 兜底默认（sys_config 缺失时）：分成 4%（400‱）/ 月维护费 ¥3000（300000 分）。 */
    private static final int DEFAULT_RATE_BP = 400;
    private static final long DEFAULT_MAINTENANCE_CENT = 300000L;

    private final GzReconSourceMapper sourceMapper;
    private final GzReconDailyMapper dailyMapper;
    private final GzReconMonthlyMapper monthlyMapper;
    private final GzReconSettleMapper settleMapper;
    private final ConfigService configService;

    @Override
    public int runDaily(LocalDate businessDay) {
        return TenantHelper.ignore(() -> {
            int count = 0;
            for (String bt : RECON_BUSINESS_TYPES) {
                long gmv = sourceMapper.sumGmvCent(bt, businessDay);
                long fee = sourceMapper.sumFeeCent(bt, businessDay);
                long refund = sourceMapper.sumRefundCent(bt, businessDay);
                // doc/11 §9.1：daily 层 settle = gmv − refund − fee（不 MAX0，如实记录差值；月度层才归零）
                long settle = gmv - refund - fee;

                GzReconDaily row = GzReconDaily.builder()
                    .businessDay(businessDay)
                    .businessType(bt)
                    .systemGmvCent(gmv)
                    .systemRefundCent(refund)
                    .systemFeeCent(fee)
                    .systemSettleCent(settle)
                    .status("generated")
                    .build();
                row.setTenantId(TENANT_ID);
                dailyMapper.upsert(row);
                count++;
            }
            log.info("[GZ-RECON-DAILY] business_day={} 跑批完成，业务线 {} 条", businessDay, count);
            return count;
        });
    }

    @Override
    public int runMonthly(YearMonth month) {
        return TenantHelper.ignore(() -> {
            String monthStr = month.toString(); // yyyy-MM
            int count = 0;
            for (String bt : RECON_BUSINESS_TYPES) {
                ReconSummaryVo agg = dailyMapper.sumDailyByMonth(bt, monthStr);
                long gmv = nz(agg == null ? null : agg.getGmvCent());
                long refund = nz(agg == null ? null : agg.getRefundCent());
                long fee = nz(agg == null ? null : agg.getChannelFeeCent());

                // doc/11 §9.2：settle = MAX(0, gmv − refund − fee)（负流水该线该月归零、不倒贴、不交叉冲抵）
                long settle = ReconCalculator.settleCent(gmv, refund, fee);
                int rateBp = rateBpOf(bt);
                long commission = ReconCalculator.commissionCent(settle, rateBp);

                GzReconMonthly row = GzReconMonthly.builder()
                    .businessMonth(monthStr)
                    .businessType(bt)
                    .gmvCent(gmv)
                    .refundCent(refund)
                    .channelFeeCent(fee)
                    .settleCent(settle)
                    .commissionRateBp(rateBp)
                    .commissionCent(commission)
                    .status("generated")
                    .build();
                row.setTenantId(TENANT_ID);
                monthlyMapper.upsert(row);
                count++;
            }
            log.info("[GZ-RECON-MONTHLY] business_month={} 跑批完成，业务线 {} 条", monthStr, count);
            return count;
        });
    }

    @Override
    public int runQuarterly(String quarter) {
        return TenantHelper.ignore(() -> {
            // D16 防御：已结算季度（status=paid/settled，钱已付乙方）遇 late 跨月退款重跑时，
            //   不静默改写已付 payable（否则审计时金额与打款流水对不上、无留痕）。仅 pending 才允许覆盖。
            String existing = settleMapper.selectStatusByQuarter(quarter, TENANT_ID);
            if ("paid".equals(existing) || "settled".equals(existing)) {
                log.warn("[GZ-RECON-SETTLE] quarter={} 已 status={}（已结算），跳过重跑不覆盖 payable（如需调整请人工处理 + 留痕）",
                    quarter, existing);
                return 0;
            }
            List<String> months = quarterMonths(quarter);
            // commission_total = Σ(当季 3 月 monthly.commission_cent)（不分 business_type → A+B 自然相加，合同 §4.2.3）
            long commissionTotal = monthlyMapper.sumCommissionByMonths(months);
            long maintenancePerMonth = maintenanceMonthlyCent();
            long maintenanceTotal = maintenancePerMonth * 3; // 月维护费 × 3（合同 §4.6）
            long payableTotal = commissionTotal + maintenanceTotal;

            GzReconSettle row = GzReconSettle.builder()
                .quarter(quarter)
                .commissionTotalCent(commissionTotal)
                .maintenanceTotalCent(maintenanceTotal)
                .payableTotalCent(payableTotal)
                .status("pending")
                .build();
            row.setTenantId(TENANT_ID);
            settleMapper.upsert(row);
            log.info("[GZ-RECON-SETTLE] quarter={} 跑批完成：commission={} + maintenance={} = payable={}（分）",
                quarter, commissionTotal, maintenanceTotal, payableTotal);
            return 1;
        });
    }

    /**
     * 季度 → 当季 3 个月份（yyyy-MM）。如 2026-Q2 → [2026-04, 2026-05, 2026-06]。
     */
    private List<String> quarterMonths(String quarter) {
        String[] parts = quarter.split("-Q");
        if (parts.length != 2) {
            throw new IllegalArgumentException("非法季度标识（期望 yyyy-Qn）: " + quarter);
        }
        int year = Integer.parseInt(parts[0]);
        int q = Integer.parseInt(parts[1]);
        if (q < 1 || q > 4) {
            throw new IllegalArgumentException("季度序号须 1-4: " + quarter);
        }
        int startMonth = (q - 1) * 3 + 1;
        return List.of(
            String.format("%d-%02d", year, startMonth),
            String.format("%d-%02d", year, startMonth + 1),
            String.format("%d-%02d", year, startMonth + 2));
    }

    /**
     * 分成比例（千分之）：取 sys_config（preorder/gacha 各自 key），缺失/非法兜底 400（4%）。
     */
    private int rateBpOf(String businessType) {
        String key = PayBusinessType.PREORDER.equals(businessType) ? KEY_RATE_PREORDER : KEY_RATE_GACHA;
        String raw = configService.getConfigValue(key);
        if (StrUtil.isBlank(raw)) {
            return DEFAULT_RATE_BP;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException ex) {
            log.warn("[GZ-RECON] sys_config {} 非法值 '{}'，兜底 {}", key, raw, DEFAULT_RATE_BP);
            return DEFAULT_RATE_BP;
        }
    }

    /**
     * 月维护费（分）：取 sys_config gz.commission.maintenance.monthly.cent，缺失/非法兜底 300000（¥3000）。
     */
    private long maintenanceMonthlyCent() {
        String raw = configService.getConfigValue(KEY_MAINTENANCE_MONTHLY);
        if (StrUtil.isBlank(raw)) {
            return DEFAULT_MAINTENANCE_CENT;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException ex) {
            log.warn("[GZ-RECON] sys_config {} 非法值 '{}'，兜底 {}", KEY_MAINTENANCE_MONTHLY, raw, DEFAULT_MAINTENANCE_CENT);
            return DEFAULT_MAINTENANCE_CENT;
        }
    }

    private static long nz(Long v) {
        return v == null ? 0L : v;
    }
}
