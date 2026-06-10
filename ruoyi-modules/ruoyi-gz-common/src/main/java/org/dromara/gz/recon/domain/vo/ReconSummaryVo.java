package org.dromara.gz.recon.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 对账聚合视图（GZ-ADMIN-105 / 复用 GZ-ADMIN-106）。
 *
 * <p>两用：</p>
 * <ol>
 *   <li>admin 对账中心 summary API：某 business_type 在月份区间内的四栏汇总 + 分成（SUM gz_recon_monthly）；</li>
 *   <li>月度跑批中间结果：SUM gz_recon_daily 的 gmv/refund/fee（settleCent/commissionCent 由 service + ReconCalculator 填）。</li>
 * </ol>
 *
 * <p>金额全 cent（Long），前端 / 100 显示元。A/B 业务线不交叉冲抵 → 每个 business_type 单独一份。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-ADMIN-105)
 */
@Data
public class ReconSummaryVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** preorder（A）/ gacha（B） */
    private String businessType;

    /** GMV（分） */
    private Long gmvCent;

    /** 退款（分） */
    private Long refundCent;

    /** 通道费（分） */
    private Long channelFeeCent;

    /** 实际到账流水（分）= MAX(0, gmv − refund − fee) */
    private Long settleCent;

    /** 分成比例（千分之，400=4%） */
    private Integer commissionRateBp;

    /** 应得分成（分）= settleCent × rateBp / 10000（向下取整） */
    private Long commissionCent;

    public ReconSummaryVo() {
    }

    public ReconSummaryVo(String businessType, Long gmvCent, Long refundCent, Long channelFeeCent) {
        this.businessType = businessType;
        this.gmvCent = gmvCent == null ? 0L : gmvCent;
        this.refundCent = refundCent == null ? 0L : refundCent;
        this.channelFeeCent = channelFeeCent == null ? 0L : channelFeeCent;
    }
}
