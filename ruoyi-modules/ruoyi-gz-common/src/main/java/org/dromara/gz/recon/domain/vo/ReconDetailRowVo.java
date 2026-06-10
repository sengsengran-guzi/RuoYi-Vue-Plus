package org.dromara.gz.recon.domain.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 对账明细行（GZ-ADMIN-105，每笔交易颗粒度）。
 *
 * <p>mapper 从 gz_pay_transaction LEFT JOIN gz_pay_refund 查出（每笔 paid/refunded 交易一行）；
 * 供 Excel 导出（转 {@link org.dromara.gz.recon.domain.excel.ReconExportRowVo}）与 admin 明细 API 共用。
 * 金额 cent（Long），导出时 / 100 转元。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-ADMIN-105)
 */
@Data
public class ReconDetailRowVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 统一支付订单号（out_trade_no，格式 域大写-yyyyMMdd-6位序号） */
    private String outTradeNo;

    /** 业务订单业务码 */
    private String businessOrderNo;

    /** 业务类型 preorder / gacha */
    private String businessType;

    /** 支付金额（分） */
    private Long amountCent;

    /** 通道费（分） */
    private Long feeCent;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime paidTime;

    /** 支付单状态 paid / refunding / refunded */
    private String status;

    /** 退款金额（分，未退款为 null） */
    private Long refundAmountCent;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime refundedTime;
}
