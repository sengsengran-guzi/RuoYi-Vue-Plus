package org.dromara.gz.common.pay.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.tenant.helper.TenantHelper;
import org.dromara.gz.common.pay.mapper.GzPayBillFundflowMapper;
import org.dromara.gz.common.pay.service.PayFundflowBillService;
import org.dromara.gz.common.pay.service.internal.IWechatPayClient;
import org.dromara.gz.common.pay.service.internal.IWechatPayClient.FundFlowBill;
import org.dromara.gz.common.pay.service.internal.bill.FundFlowBillCsvBuilder;
import org.dromara.gz.common.pay.service.internal.bill.FundFlowBillCsvParser;
import org.dromara.gz.common.pay.service.internal.bill.FundFlowBillRow;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 资金账单对账服务实现（GZ-PAY-104）。
 *
 * <p>实现 doc/10 §10 E6「按 transaction 级实际 fee 字段汇总，不依赖固定费率」的<b>数据准备前置</b>：
 * 把每笔真实通道费从资金账单灌进 {@code gz_pay_transaction.fee_cent}。</p>
 *
 * <p><b>幂等</b>（AC6）：① {@code gz_pay_bill_fundflow} 按 {@code (tenant_id, bill_date, transaction_id)}
 * UNIQUE upsert（同日重跑不翻倍）；② {@code fee_cent} 覆盖式回写（{@code SET fee_cent=?} 绝对值，非累加）。</p>
 *
 * <p><b>不静默回写脏数据</b>（AC3）：下载文件自算 sha1 与微信声明 hash 不匹配 → 抛 {@link ServiceException}
 * 中断（job 壳 catch 转 SnailJob failure 告警），不落库不回写。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-PAY-104)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PayFundflowBillServiceImpl implements PayFundflowBillService {

    /**
     * cron 上下文租户 ID（V1.0 单租户 '1001'，与 {@link org.dromara.gz.common.dashboard.service.impl.GzDashboardServiceImpl}
     * 同口径）。cron 无登录态 → mapper 需显式传 tenant_id；在 {@code TenantHelper.ignore} 下旁路拦截器自动注入。
     * 未来多租户时此处改为遍历租户列表分批。
     */
    private static final String TENANT_ID = "1001";

    private final IWechatPayClient wechatPayClient;
    private final GzPayBillFundflowMapper billFundflowMapper;

    @Override
    public ReconcileResult reconcileFee(LocalDate bizDate) {
        if (bizDate == null) {
            throw new ServiceException("账单业务日不能为空");
        }
        // cron 无登录态 → 关多租户拦截器（V1.0 仅 '1001'）；mapper 内显式传 tenant_id 行为完全确定。
        return TenantHelper.ignore(() -> doReconcile(bizDate));
    }

    private ReconcileResult doReconcile(LocalDate bizDate) {
        // ① 拉账单（已解压 CSV + 微信声明 sha1）
        FundFlowBill bill = wechatPayClient.downloadFundFlowBill(bizDate);

        // ② sha1 校验（AC3）：不匹配抛错告警，不静默回写脏数据
        String actualHash = FundFlowBillCsvBuilder.sha1Hex(bill.csvContent());
        if (bill.hashValue() != null && !bill.hashValue().equalsIgnoreCase(actualHash)) {
            throw new ServiceException(String.format(
                "资金账单 hash 校验失败 bill_date=%s 微信声明=%s 实算=%s（疑似下载损坏 / 篡改，中断不回写）",
                bizDate, bill.hashValue(), actualHash));
        }

        // ③ 解析 CSV 聚合每笔 fee（仅 feeCent > 0）
        List<FundFlowBillRow> rows = FundFlowBillCsvParser.parse(bill.csvContent());

        // ④ 逐笔幂等 upsert gz_pay_bill_fundflow + 回写 gz_pay_transaction.fee_cent（覆盖式）
        Set<String> billTxnIds = new LinkedHashSet<>(rows.size());
        List<String> transactionNotFound = new ArrayList<>();
        int matched = 0;
        for (FundFlowBillRow row : rows) {
            billTxnIds.add(row.transactionId());
            // upsert 账单明细（AC6：UNIQUE 命中覆盖 fee/out_trade_no/raw_line，不翻倍）
            billFundflowMapper.upsertBill(TENANT_ID, bizDate, row.transactionId(),
                row.outTradeNo(), row.feeCent(), row.rawLine());
            // 覆盖式回写 fee_cent（AC4：仅 status='paid'；test 单照常回写）
            int affected = billFundflowMapper.updateFeeCentByTransactionId(
                TENANT_ID, row.transactionId(), row.feeCent());
            if (affected >= 1) {
                matched++;
            } else {
                // 账单有该 transaction_id 但系统无对应 paid 交易 → 孤儿账单行（AC5 ①）
                transactionNotFound.add(row.transactionId());
            }
        }

        // ⑤ 缺账检测（AC5 ②）：系统当日 status='paid' 交易号集合 − 账单交易号集合 = 账单缺其行
        List<String> paidTxnIds = billFundflowMapper.selectPaidTransactionIdsByDate(TENANT_ID, bizDate);
        List<String> paidNoBill = new ArrayList<>();
        for (String paidTxnId : paidTxnIds) {
            if (paidTxnId != null && !billTxnIds.contains(paidTxnId)) {
                paidNoBill.add(paidTxnId);
            }
        }

        ReconcileResult result = new ReconcileResult(
            bizDate, billTxnIds.size(), matched, transactionNotFound, paidNoBill);

        // ⑥ 告警（AC5）：两类计数 > 0 打 log.warn 含明细 transaction_id 列表，不依赖人眼看日志
        if (result.transactionNotFoundCount() > 0) {
            log.warn("[gz-pay-bill] 孤儿账单行（账单有但系统无 paid 交易）bill_date={} count={} txnIds={}",
                bizDate, result.transactionNotFoundCount(), transactionNotFound);
        }
        if (result.paidNoBillCount() > 0) {
            log.warn("[gz-pay-bill] 缺账（系统当日 paid 交易账单缺其行）bill_date={} count={} txnIds={}",
                bizDate, result.paidNoBillCount(), paidNoBill);
        }
        log.info("[gz-pay-bill] 对账完成 bill_date={} billTotal={} matched={} transactionNotFound={} paidNoBill={}",
            bizDate, result.billTotal(), matched, result.transactionNotFoundCount(), result.paidNoBillCount());
        return result;
    }
}
