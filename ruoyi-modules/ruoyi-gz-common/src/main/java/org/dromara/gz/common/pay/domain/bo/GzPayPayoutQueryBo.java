package org.dromara.gz.common.pay.domain.bo;

import lombok.Data;

import java.io.Serializable;

/**
 * 反向打款单列表查询条件（GZ-PAY-105 admin 列表筛选）。
 *
 * @author kevin-coder (sensenran-guzi · GZ-PAY-105)
 */
@Data
public class GzPayPayoutQueryBo implements Serializable {

    /** 状态（created / processing / success / failed / cancelled，空 = 全部） */
    private String status;

    /** 业务出账单号（精确） */
    private String outPayoutNo;

    /** 业务订单号 = 回收预约号（精确） */
    private String businessOrderNo;

    /** 微信侧转账单号（精确） */
    private String payoutId;
}
