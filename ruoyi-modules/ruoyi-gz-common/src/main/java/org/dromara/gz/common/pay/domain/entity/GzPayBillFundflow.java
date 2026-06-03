package org.dromara.gz.common.pay.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.dromara.common.tenant.core.TenantEntity;

import java.io.Serial;
import java.time.LocalDate;

/**
 * gz_pay_bill_fundflow — 微信资金账单明细 entity（GZ-PAY-104 AC 1）。
 *
 * <p>字段口径权威：doc/11 F4.3 / F9.2。业务流权威：doc/10 §10 对账中心流（E6）。</p>
 *
 * <p><b>来源</b>：每日 SnailJob 拉前一业务日 {@code /v3/bill/fundflowbill}（资金账单）解析 CSV，
 * 每行落一笔（按 {@code uk_tenant_bill_date_transaction_id} UNIQUE 幂等 upsert）。
 * 是 {@code gz_pay_transaction.fee_cent} 回写的真源（V3 回调 body 不含 fee）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-PAY-104)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("gz_pay_bill_fundflow")
public class GzPayBillFundflow extends TenantEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 账单业务日（北京时间，跑批默认前一业务日） */
    private LocalDate billDate;

    /** 微信交易号（关联 gz_pay_transaction.transaction_id 回写 fee_cent） */
    private String transactionId;

    /** 业务订单号（账单冗余列，便于人工追溯；缺失可 null） */
    private String outTradeNo;

    /** 该笔真实通道手续费（分，从资金账单"手续费"列解析） */
    private Long feeCent;

    /** 原始 CSV 行（便于争议追溯） */
    private String rawLine;

    /** 软删（0=正常 / 1=删除） */
    @TableLogic
    private String delFlag;
}
