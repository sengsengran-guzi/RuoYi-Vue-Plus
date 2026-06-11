package org.dromara.gz.recon.domain.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * GZ-ADMIN-106 数据看板 V1.1 交易盘面聚合视图（单接口一次返所有卡片）。
 *
 * <p>业务线分流统一 {@code gz_pay_transaction.business_type}（preorder=A / gacha=B，test exclude）；
 * 本月 GMV / 退款 / 实际到账复用 ADMIN-105 {@code IGzReconQueryService}（不重写 4% 口径，避免双口径漂移）。
 * 金额全 cent（前端 / 100 显示元）。盲盒语义：开盒数 / 平均出货价值（禁抽奖 / 中奖）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-ADMIN-106)
 */
@Data
public class GzDashboardV11SummaryVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 今日订单数（A 预定 / B 扭蛋，当日 status=paid 笔数） */
    private long todayOrderCountPreorder;
    private long todayOrderCountGacha;

    /** 今日 GMV（分，A / B） */
    private long todayGmvCentPreorder;
    private long todayGmvCentGacha;

    /** 本月累计 GMV（分，A / B，复用 ADMIN-105） */
    private long monthGmvCentPreorder;
    private long monthGmvCentGacha;

    /** 本月退款（分，A / B） */
    private long monthRefundCentPreorder;
    private long monthRefundCentGacha;

    /** 本月实际到账（分，A / B = MAX(0, GMV − 退款 − 通道费)） */
    private long monthSettleCentPreorder;
    private long monthSettleCentGacha;

    /** 扭蛋本月开盒数（gz_gacha_draw 本月，盲盒语义） */
    private long gachaOpenCount;

    /** 扭蛋本月平均出货价值（分）= SUM(prize_snapshot_json.referenceValueCent) / 开盒数 */
    private long gachaAvgValueCent;

    /** 待发货订单数（gz_ord_order business_status='paid' AND logistics_status='in_japan'，红色提醒甲方推进物流） */
    private long pendingShipCount;

    /** 热销预购商品 Top10（sales_count 降序） */
    private List<TopProduct> topProducts;

    /**
     * 热销预购商品行。
     */
    @Data
    public static class TopProduct implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        @JsonSerialize(using = ToStringSerializer.class)
        private Long productId;

        /** 商品名 */
        private String name;

        /** 累计销量 */
        private Long salesCount;
    }
}
