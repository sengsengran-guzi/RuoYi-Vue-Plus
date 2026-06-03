package org.dromara.gz.common.pay.service.internal.bill;

/**
 * 资金账单解析后的单笔聚合结果（GZ-PAY-104）。
 *
 * <p>一个 {@code transactionId} 一条：{@code feeCent} 已聚合该交易号下所有「业务类型=手续费 + 收支类型=支出」
 * 行的金额（分）。{@code rawLine} 保留账单里该交易号的代表性原始 CSV 行（手续费行优先，便于追溯）。</p>
 *
 * @param transactionId 微信支付业务单号（= gz_pay_transaction.transaction_id）
 * @param outTradeNo    业务凭证号（= out_trade_no，可能为空）
 * @param feeCent       该交易聚合后的真实手续费（分，≥ 0）
 * @param rawLine       代表性原始 CSV 行（追溯用）
 *
 * @author kevin-coder (sensenran-guzi · GZ-PAY-104)
 */
public record FundFlowBillRow(String transactionId, String outTradeNo, long feeCent, String rawLine) {
}
