package org.dromara.gz.recon.service;

import org.dromara.gz.recon.domain.vo.GzDashboardV11SummaryVO;

/**
 * GZ-ADMIN-106 数据看板 V1.1 交易盘面 service（单聚合接口）。
 *
 * <p>本月 GMV / 退款 / 实际到账复用 ADMIN-105 {@link IGzReconQueryService}（单一口径，不重写 4% 分成计算）；
 * 今日订单/GMV、扭蛋开盒、待发货、热销 Top10 自有聚合。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-ADMIN-106)
 */
public interface IGzReconDashboardService {

    /**
     * 一次性聚合 V1.1 交易盘面所有卡片（单接口，同一快照口径一致）。
     */
    GzDashboardV11SummaryVO getV11Summary();
}
