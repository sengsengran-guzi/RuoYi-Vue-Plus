package org.dromara.gz.recon.domain.excel;

import cn.idev.excel.annotation.ExcelProperty;
import cn.idev.excel.annotation.write.style.ColumnWidth;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 对账明细导出行（GZ-ADMIN-105 AC 8 / 合同 §4.2.1 / doc/11 F9.5）。
 *
 * <p>每笔交易一行，列 = 订单号 / 业务单号 / 金额(元) / 支付时间 / 状态 / 通道费(元) / 退款金额(元) / 退款时间。
 * <b>不做透视表 / 不合并单元格 / 不做月度小计行</b>（让甲方一键复制到银行流水 Excel 比对）；
 * 末尾追加四项汇总行（GMV / 退款 / 通道费 / 实际到账），由 service 拼装为一条 statusText="【本期汇总】" 的行。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-ADMIN-105)
 */
@Data
public class ReconExportRowVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @ColumnWidth(24)
    @ExcelProperty(value = "支付订单号")
    private String outTradeNo;

    @ColumnWidth(22)
    @ExcelProperty(value = "业务订单号")
    private String businessOrderNo;

    @ColumnWidth(14)
    @ExcelProperty(value = "金额(元)")
    private String amountYuan;

    @ColumnWidth(20)
    @ExcelProperty(value = "支付时间")
    private String paidTimeText;

    @ColumnWidth(12)
    @ExcelProperty(value = "状态")
    private String statusText;

    @ColumnWidth(14)
    @ExcelProperty(value = "通道费(元)")
    private String feeYuan;

    @ColumnWidth(14)
    @ExcelProperty(value = "退款金额(元)")
    private String refundYuan;

    @ColumnWidth(20)
    @ExcelProperty(value = "退款时间")
    private String refundedTimeText;
}
