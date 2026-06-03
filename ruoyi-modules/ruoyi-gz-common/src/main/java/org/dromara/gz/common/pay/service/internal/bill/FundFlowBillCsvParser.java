package org.dromara.gz.common.pay.service.internal.bill;

import org.dromara.common.core.exception.ServiceException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 微信资金账单（{@code /v3/bill/fundflowbill}）CSV 解析器（GZ-PAY-104 AC3）。
 *
 * <p><b>资金账单格式</b>（微信支付商户文档「资金账单文件格式说明」）：</p>
 * <ul>
 *   <li>文件四段：明细数据表头 / 明细数据 / 汇总数据表头 / 汇总数据。本解析器只取<b>明细数据</b>段。</li>
 *   <li>明细表头列序：记账时间 / <b>微信支付业务单号</b> / 资金流水单号 / 业务名称 / <b>业务类型</b> /
 *       <b>收支类型</b> / <b>收支金额(元)</b> / 账户结余(元) / 资金变更提交申请人 / 备注 / <b>业务凭证号</b>。</li>
 *   <li>每个字段前加 1 个反引号 {@code `}（防 Excel 科学计数法），解析时剥除。</li>
 *   <li>账单<b>无独立「手续费」列</b>：手续费体现为单独的明细行 —— 同一「微信支付业务单号」下
 *       「业务类型=手续费 + 收支类型=支出」的行，其「收支金额(元)」即该笔手续费（doc/10 §10 E6）。</li>
 * </ul>
 *
 * <p><b>聚合口径</b>：按「微信支付业务单号」分组，{@code feeCent} = 该组所有手续费行金额之和 ×100（元→分，
 * 四舍五入）。out_trade_no 取该组任意行的「业务凭证号」。只产出 feeCent &gt; 0 的交易（无手续费行的交易不回写）。</p>
 *
 * <p>列名按表头动态定位（不写死下标），对微信小幅调整列序有韧性。表头缺关键列 → 抛 {@link ServiceException}。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-PAY-104)
 */
public final class FundFlowBillCsvParser {

    /** 明细表头：微信支付业务单号（= transaction_id） */
    public static final String COL_TRANSACTION_ID = "微信支付业务单号";
    /** 明细表头：业务类型（交易 / 退款 / 手续费 ...） */
    public static final String COL_BUSINESS_TYPE = "业务类型";
    /** 明细表头：收支类型（收入 / 支出） */
    public static final String COL_INOUT_TYPE = "收支类型";
    /** 明细表头：收支金额(元) */
    public static final String COL_AMOUNT_YUAN = "收支金额(元)";
    /** 明细表头：业务凭证号（= out_trade_no，商户单号） */
    public static final String COL_OUT_TRADE_NO = "业务凭证号";

    /** 手续费行标识（业务类型 contains） */
    private static final String FEE_BUSINESS_TYPE = "手续费";
    /** 支出收支类型 */
    private static final String INOUT_EXPENSE = "支出";

    private FundFlowBillCsvParser() {
    }

    /**
     * 解析资金账单 CSV，聚合每笔交易的真实手续费（分）。
     *
     * @param csvContent 已 gzip 解压的完整 CSV 文本
     * @return 按 transaction_id 聚合的 fee 行列表（仅 feeCent &gt; 0；保留遇见顺序）
     * @throws ServiceException CSV 为空 / 表头缺关键列 / 金额非法
     */
    public static List<FundFlowBillRow> parse(String csvContent) {
        if (csvContent == null || csvContent.isBlank()) {
            throw new ServiceException("资金账单内容为空，无法解析");
        }
        String[] lines = csvContent.split("\r\n|\r|\n");

        // ① 定位明细表头行（第一行非空且含「记账时间」/「微信支付业务单号」）
        int headerIdx = -1;
        for (int i = 0; i < lines.length; i++) {
            String l = lines[i].trim();
            if (l.isEmpty()) {
                continue;
            }
            if (l.contains(COL_TRANSACTION_ID)) {
                headerIdx = i;
                break;
            }
        }
        if (headerIdx < 0) {
            throw new ServiceException("资金账单缺明细表头（未找到列「" + COL_TRANSACTION_ID + "」）");
        }

        // ② 表头列名 → 下标（剥反引号）
        String[] headers = splitCsvLine(lines[headerIdx]);
        Map<String, Integer> colIdx = new LinkedHashMap<>();
        for (int i = 0; i < headers.length; i++) {
            colIdx.put(headers[i].trim(), i);
        }
        int idxTxn = requireCol(colIdx, COL_TRANSACTION_ID);
        int idxBizType = requireCol(colIdx, COL_BUSINESS_TYPE);
        int idxInOut = requireCol(colIdx, COL_INOUT_TYPE);
        int idxAmount = requireCol(colIdx, COL_AMOUNT_YUAN);
        // 业务凭证号为商户自定义列，少数账单可能缺；缺则 out_trade_no 留 null（非致命）
        Integer idxOutTradeNo = colIdx.get(COL_OUT_TRADE_NO);

        // ③ 逐明细行聚合（遇汇总表头 / 空行 → 明细段结束）
        // 用 LinkedHashMap 保留首次遇见顺序，便于单测断言稳定
        Map<String, long[]> feeByTxn = new LinkedHashMap<>();        // txnId -> [feeCent 累加]
        Map<String, String> outTradeNoByTxn = new LinkedHashMap<>(); // txnId -> out_trade_no
        Map<String, String> rawLineByTxn = new LinkedHashMap<>();    // txnId -> 代表性原始行（手续费行优先）

        for (int i = headerIdx + 1; i < lines.length; i++) {
            String rawLine = lines[i];
            String trimmed = rawLine.trim();
            if (trimmed.isEmpty()) {
                continue; // 空行跳过（不结束，账单中段偶有空行）
            }
            // 汇总段表头（如「资金流水总笔数,...」）不含交易号 → 明细段结束
            if (trimmed.contains("资金流水总笔数") || trimmed.startsWith("`资金流水总笔数")) {
                break;
            }
            String[] cols = splitCsvLine(rawLine);
            // 列数不足以取到关键列 → 视为非明细行（汇总/脏行），结构性缺列抛错（AC：列缺失抛错）
            int maxNeeded = Math.max(Math.max(idxTxn, idxBizType), Math.max(idxInOut, idxAmount));
            if (cols.length <= maxNeeded) {
                throw new ServiceException("资金账单明细行列数不足（行 " + (i + 1) + "，期望至少 "
                    + (maxNeeded + 1) + " 列，实际 " + cols.length + " 列）：" + trimmed);
            }
            String txnId = cols[idxTxn].trim();
            if (txnId.isEmpty()) {
                continue; // 无交易号的明细行（极少）跳过
            }
            String bizType = cols[idxBizType].trim();
            String inOut = cols[idxInOut].trim();
            String outTradeNo = idxOutTradeNo != null && idxOutTradeNo < cols.length
                ? cols[idxOutTradeNo].trim() : null;

            // 记录 out_trade_no（任意行；不覆盖已有非空值）
            if (outTradeNo != null && !outTradeNo.isEmpty()) {
                outTradeNoByTxn.putIfAbsent(txnId, outTradeNo);
            }

            // 只聚合「业务类型含手续费 + 收支类型=支出」行的金额
            if (bizType.contains(FEE_BUSINESS_TYPE) && INOUT_EXPENSE.equals(inOut)) {
                long feeCent = yuanToCent(cols[idxAmount].trim(), i + 1);
                feeByTxn.computeIfAbsent(txnId, k -> new long[1])[0] += feeCent;
                rawLineByTxn.put(txnId, trimmed); // 手续费行作为代表性原始行
            }
        }

        // ④ 产出（仅 feeCent > 0）
        List<FundFlowBillRow> result = new ArrayList<>(feeByTxn.size());
        for (Map.Entry<String, long[]> e : feeByTxn.entrySet()) {
            long fee = e.getValue()[0];
            if (fee <= 0) {
                continue;
            }
            String txnId = e.getKey();
            result.add(new FundFlowBillRow(
                txnId,
                outTradeNoByTxn.get(txnId),
                fee,
                rawLineByTxn.getOrDefault(txnId, "")));
        }
        return result;
    }

    private static int requireCol(Map<String, Integer> colIdx, String name) {
        Integer idx = colIdx.get(name);
        if (idx == null) {
            throw new ServiceException("资金账单明细表头缺列「" + name + "」（实际表头: " + colIdx.keySet() + "）");
        }
        return idx;
    }

    /**
     * 元 → 分（×100 四舍五入）。账单金额为正数字符串（手续费支出行金额已是正值）。
     */
    private static long yuanToCent(String yuan, int lineNo) {
        if (yuan == null || yuan.isEmpty()) {
            throw new ServiceException("资金账单第 " + lineNo + " 行金额为空");
        }
        try {
            // 去千分位逗号（账单一般无，但容错）
            BigDecimal bd = new BigDecimal(yuan.replace(",", ""));
            return bd.movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact();
        } catch (NumberFormatException | ArithmeticException ex) {
            throw new ServiceException("资金账单第 " + lineNo + " 行金额非法: " + yuan);
        }
    }

    /**
     * 拆 CSV 行：以英文逗号分隔，剥每字段前导反引号 {@code `}（微信防科学计数法字符）。
     *
     * <p>微信资金账单字段不含内嵌逗号（商户自定义字段已转义 \r/\n、特殊字符），故按简单逗号 split
     * 足够；为稳健仍剥首个反引号。</p>
     */
    static String[] splitCsvLine(String line) {
        String[] parts = line.split(",", -1);
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i];
            if (p.startsWith("`")) {
                p = p.substring(1);
            }
            parts[i] = p;
        }
        return parts;
    }
}
