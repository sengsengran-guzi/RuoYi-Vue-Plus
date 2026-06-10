package org.dromara.gz.recon.service;

import java.time.LocalDate;
import java.time.YearMonth;

/**
 * 对账跑批 service（GZ-ADMIN-105）。
 *
 * <p><b>可单测纯逻辑</b>（不依赖 SnailJob 运行时）：3 个跑批方法由 {@code @JobExecutor} 薄壳转调。
 * 口径权威 doc/11 §9.1 / §9.2 / §9.4 + §4.6。业务流 doc/10 §10.N1 / N2 / N4。</p>
 *
 * <p>幂等：daily 按 (business_day, business_type) UPSERT、monthly 按 (business_month, business_type) UPSERT、
 * settle 按 quarter UPSERT —— 同周期重跑覆盖不累加。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-ADMIN-105)
 */
public interface IGzReconBatchService {

    /**
     * 每日跑批（doc/11 §9.1）：对 preorder（A）/ gacha（B）各算一条日对账行。
     * <p>system_settle_cent = gmv − refund − fee（<b>daily 层不 MAX0，如实记录可负</b>，月度层才归零）。</p>
     *
     * @param businessDay 业务日（北京时间，通常为前一日）
     * @return 处理条数（正常 = 2，A/B 各一条）
     */
    int runDaily(LocalDate businessDay);

    /**
     * 月度跑批（doc/11 §9.2）：聚合当月 daily（A/B 各一条月对账单）。
     * <p>settle_cent = MAX(0, Σgmv − Σrefund − Σfee)（<b>负流水该线该月归零、不交叉冲抵</b>）；
     * commission_cent = settle_cent × rateBp / 10000（向下取整，rateBp 取 sys_config）。</p>
     *
     * @param month 业务月
     * @return 处理条数（正常 = 2）
     */
    int runMonthly(YearMonth month);

    /**
     * 季度跑批（doc/11 §9.4）：commission_total = Σ(当季 3 月 monthly.commission_cent)（A+B 相加）；
     * maintenance_total = 月维护费 × 3（合同 §4.6）；payable_total = commission_total + maintenance_total。
     *
     * @param quarter 季度标识 yyyy-Q1 / Q2 / Q3 / Q4
     * @return 处理条数（正常 = 1）
     */
    int runQuarterly(String quarter);
}
