package org.dromara.gz.common.pay.service;

import java.time.LocalDate;
import java.util.List;

/**
 * 资金账单对账服务（GZ-PAY-104）。
 *
 * <p><b>职责</b>：每日拉前一业务日微信资金账单（{@code /v3/bill/fundflowbill}），解析 CSV 得到每笔交易的
 * 真实通道手续费（分），先幂等落 {@code gz_pay_bill_fundflow}，再按 {@code transaction_id} <b>覆盖式</b>
 * 回写 {@code gz_pay_transaction.fee_cent}。这是 {@code fee_cent} 的<b>唯一真源</b>（V3 回调 body 不含 fee，
 * 固定费率不算数，doc/10 §10 E6 / doc/11 F4.3 / F9.2）。</p>
 *
 * <p><b>边界</b>（任务卡备注）：本服务只负责"把真实笔费灌进 {@code gz_pay_transaction.fee_cent}"；
 * 按 {@code business_type} 汇总 / {@code system_fee_cent} 计算 / 业务线 attribution 在 D11 GZ-ADMIN-105，
 * 本服务<b>不建 gz_recon_* 表、不算汇总</b>。</p>
 *
 * <p><b>调度</b>：核心可测逻辑全在 {@link #reconcileFee(LocalDate)}；SnailJob 薄壳
 * {@code org.dromara.gz.common.pay.job.PayFundflowBillJob} 仅触发，不含业务逻辑（AC2）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-PAY-104)
 */
public interface PayFundflowBillService {

    /**
     * 拉账单 + 解析 + 回写一个业务日的真实通道手续费（AC3-AC6 全链路）。
     *
     * <p>流程：① 调 {@link org.dromara.gz.common.pay.service.internal.IWechatPayClient#downloadFundFlowBill}
     * 拿已解压 CSV + 微信声明 sha1 → ② 自算 sha1 校验，不匹配 throw（AC3，不静默回写脏数据）→
     * ③ 解析 CSV 聚合每笔 fee → ④ 逐笔幂等 upsert {@code gz_pay_bill_fundflow}（AC6 不翻倍）→
     * ⑤ 按 {@code transaction_id} 覆盖式回写 {@code gz_pay_transaction.fee_cent}（仅 status='paid'，AC4/AC6）→
     * ⑥ 缺账 / 孤儿对账统计（AC5）。</p>
     *
     * <p><b>多租户</b>：cron 无登录态 → 在 {@code TenantHelper.ignore} 下全租户扫齐（V1 仅 1001，与
     * {@code expireTimeoutOrders} 同思路）。回写 / upsert 显式传 tenant_id。</p>
     *
     * @param bizDate 账单业务日（北京时间；跑批默认前一日，由 job 壳计算后传入）
     * @return 结构化对账结果（账单总笔数 / 命中回写 / 孤儿账单 / 缺账，AC5 可断言）
     */
    ReconcileResult reconcileFee(LocalDate bizDate);

    /**
     * 对账结构化结果（AC5 可断言，非主观看日志）。
     *
     * @param billDate            账单业务日
     * @param billTotal           账单解析出的交易笔数（feeCent &gt; 0 的去重 transaction_id 数）
     * @param matched             命中回写数（账单 transaction_id 在系统找到 status='paid' 交易并回写 fee_cent）
     * @param transactionNotFound 孤儿账单行：账单有 transaction_id 但系统无对应 paid 交易（明细 transaction_id 列表）
     * @param paidNoBill          缺账：系统有当日 status='paid' 交易但账单缺其行（明细 transaction_id 列表）
     */
    record ReconcileResult(LocalDate billDate, int billTotal, int matched,
                           List<String> transactionNotFound, List<String> paidNoBill) {

        /** 孤儿账单计数（AC5 ①） */
        public int transactionNotFoundCount() {
            return transactionNotFound == null ? 0 : transactionNotFound.size();
        }

        /** 缺账计数（AC5 ②） */
        public int paidNoBillCount() {
            return paidNoBill == null ? 0 : paidNoBill.size();
        }

        /** 是否存在任一类对不上（任一计数 &gt; 0 → job 壳据此返回 warning 文案） */
        public boolean hasMismatch() {
            return transactionNotFoundCount() > 0 || paidNoBillCount() > 0;
        }
    }
}
