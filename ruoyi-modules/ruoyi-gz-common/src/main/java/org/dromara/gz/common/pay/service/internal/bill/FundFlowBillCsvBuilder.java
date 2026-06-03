package org.dromara.gz.common.pay.service.internal.bill;

import org.dromara.common.core.exception.ServiceException;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 资金账单 CSV 构造器（GZ-PAY-104 mock + 单测用）。
 *
 * <p>按微信「资金账单文件格式说明」结构生成可控 CSV（明细表头 + 明细行 + 汇总表头 + 汇总行），
 * 每个字段加反引号前缀（与真实账单一致，解析器会剥）。每笔交易生成「交易行（收入）」+「手续费行（支出）」
 * 两条；手续费行的「收支金额(元)」即该笔真实手续费。{@link FundFlowBillCsvParser} 与本构造器列序一致。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-PAY-104)
 */
public final class FundFlowBillCsvBuilder {

    /** 明细数据表头（列序对齐 {@link FundFlowBillCsvParser} 期望的关键列） */
    private static final String DETAIL_HEADER =
        "记账时间,微信支付业务单号,资金流水单号,业务名称,业务类型,收支类型,收支金额(元),账户结余(元),资金变更提交申请人,备注,业务凭证号";

    /** 汇总数据表头 */
    private static final String SUMMARY_HEADER =
        "资金流水总笔数,收入笔数,收入金额,支出笔数,支出金额";

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private FundFlowBillCsvBuilder() {
    }

    /**
     * 一笔交易的构造入参。
     *
     * @param transactionId 微信支付业务单号
     * @param outTradeNo    业务凭证号（商户单号）
     * @param amountYuan    交易金额（元）
     * @param feeYuan       手续费（元）；&lt;= 0 时不生成手续费行（模拟「该交易账单里没有手续费行」）
     */
    public record MockTxn(String transactionId, String outTradeNo, String amountYuan, String feeYuan) {
    }

    /**
     * 构造资金账单 CSV。
     *
     * @param billDate 账单业务日
     * @param txns     交易列表
     * @return 完整 CSV 文本（CRLF 行分隔，每字段反引号前缀）
     */
    public static String build(LocalDate billDate, List<MockTxn> txns) {
        LocalDateTime base = billDate.atTime(10, 0, 0);
        StringBuilder sb = new StringBuilder();
        sb.append(prefix(DETAIL_HEADER)).append("\r\n");

        int incomeCnt = 0;
        int expenseCnt = 0;
        BigDecimal incomeSum = BigDecimal.ZERO;
        BigDecimal expenseSum = BigDecimal.ZERO;

        int seq = 0;
        for (MockTxn t : txns) {
            // 交易行（收入）
            String flowNoPay = String.format("F%s%04d", billDate.toString().replace("-", ""), ++seq);
            sb.append(detailRow(base.plusMinutes(seq), t.transactionId(), flowNoPay,
                "交易", "交易", "收入", t.amountYuan(), "0.00", "系统", "谷子宇宙交易", t.outTradeNo()))
                .append("\r\n");
            incomeCnt++;
            incomeSum = incomeSum.add(new BigDecimal(t.amountYuan()));

            // 手续费行（支出）—— feeYuan > 0 才生成（模拟缺手续费行的交易）
            if (t.feeYuan() != null && new BigDecimal(t.feeYuan()).signum() > 0) {
                String flowNoFee = String.format("F%s%04d", billDate.toString().replace("-", ""), ++seq);
                sb.append(detailRow(base.plusMinutes(seq), t.transactionId(), flowNoFee,
                    "交易手续费", "手续费", "支出", t.feeYuan(), "0.00", "系统", "手续费", t.outTradeNo()))
                    .append("\r\n");
                expenseCnt++;
                expenseSum = expenseSum.add(new BigDecimal(t.feeYuan()));
            }
        }

        // 汇总段
        sb.append(prefix(SUMMARY_HEADER)).append("\r\n");
        sb.append(prefix(String.join(",",
            String.valueOf(incomeCnt + expenseCnt),
            String.valueOf(incomeCnt), incomeSum.toPlainString(),
            String.valueOf(expenseCnt), expenseSum.toPlainString()))).append("\r\n");
        return sb.toString();
    }

    /**
     * 内置默认 mock 账单（dev 启动 / 无 override 时）：2 笔正常交易 + 手续费。
     */
    public static String defaultMockBill(LocalDate billDate) {
        List<MockTxn> txns = new ArrayList<>();
        txns.add(new MockTxn("4200MOCK00000001" + billDate.toString().replace("-", ""),
            "TEST-" + billDate.toString().replace("-", "") + "-000001", "0.01", "0.01"));
        txns.add(new MockTxn("4200MOCK00000002" + billDate.toString().replace("-", ""),
            "PREORD-" + billDate.toString().replace("-", "") + "-000001", "99.00", "0.59"));
        return build(billDate, txns);
    }

    private static String detailRow(LocalDateTime time, String txnId, String flowNo, String bizName,
                                    String bizType, String inOut, String amountYuan, String balance,
                                    String applicant, String remark, String outTradeNo) {
        return prefix(String.join(",",
            time.format(TS), txnId, flowNo, bizName, bizType, inOut,
            amountYuan, balance, applicant, remark, outTradeNo == null ? "" : outTradeNo));
    }

    /** 每个逗号分隔字段前加反引号（与真实微信账单一致）。 */
    private static String prefix(String csvLine) {
        String[] parts = csvLine.split(",", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append("`").append(parts[i]);
        }
        return sb.toString();
    }

    /**
     * 计算文本 sha1 十六进制（与微信账单 hash_value 同算法，用于 mock 返回匹配 hash / service 自校验）。
     *
     * @param content 文本
     * @return 小写 hex sha1
     */
    public static String sha1Hex(String content) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] digest = md.digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new ServiceException("SHA-1 不可用: " + e.getMessage());
        }
    }
}
