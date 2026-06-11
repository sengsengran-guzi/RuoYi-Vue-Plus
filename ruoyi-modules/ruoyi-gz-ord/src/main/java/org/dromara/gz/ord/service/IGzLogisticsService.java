package org.dromara.gz.ord.service;

import org.dromara.gz.ord.domain.dto.LogisticsCarrierUpdateDto;
import org.dromara.gz.ord.domain.dto.LogisticsForwardDto;
import org.dromara.gz.ord.domain.dto.LogisticsRollbackDto;

/**
 * 跨境物流 2 态推进 service（GZ-ADMIN-104，doc/10 §9 C1）。
 *
 * <p>mp 店员端（主形态）+ plus-ui owner 兜底共用本 service，跨业务按 business_type 分流 UPDATE
 * gz_ord_order / gz_gacha_order；状态机校验 / 9 项快递校验 / 必录校验 / 审计写入集中此处（D2 单点）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-ADMIN-104)
 */
public interface IGzLogisticsService {

    /**
     * 推进到下一态（in_japan→in_china_dispatching 必录快递+单号；in_china_dispatching→delivered）。
     * <p>已是 delivered → 报错；business_status=refunded → 报错（E6）；写 gz_logistics_audit（status_forward）。</p>
     */
    void forward(LogisticsForwardDto dto);

    /**
     * 改单号（仅 in_china_dispatching 可改，不改状态 / 不重置 cn_dispatched_at，doc/10 §9.N9）。
     * <p>写 gz_logistics_audit（carrier_update，记 from/to）。</p>
     */
    void updateCarrier(LogisticsCarrierUpdateDto dto);

    /**
     * owner 回退到上一态（reason 必填，doc/10 §9.N8）。in_japan 无上一态 → 报错。
     * <p>写 gz_logistics_audit（status_rollback，记 from/to/reason）。</p>
     */
    void rollback(LogisticsRollbackDto dto);
}
