package org.dromara.gz.common.pay.domain.bo;

import lombok.Data;

import java.io.Serializable;

/**
 * 支付订单列表查询条件（GZ-PAY-001 admin 列表筛选）。
 *
 * @author kevin-coder (sensenran-guzi · GZ-PAY-001)
 */
@Data
public class GzPayTransactionQueryBo implements Serializable {

    /** 业务类型（test / preorder / gacha / pindou，空 = 全部） */
    private String businessType;

    /** 状态（created / pending / paid / timeout / closed / failed，空 = 全部） */
    private String status;

    /** 业务订单号（精确） */
    private String outTradeNo;

    /** 微信交易号（精确） */
    private String transactionId;
}
