package org.dromara.gz.ord.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 订单物流当前态（GZ-ADMIN-104，mapper 查 gz_ord_order/gz_gacha_order 出参，供状态机校验）。
 *
 * @author kevin-coder (sensenran-guzi · GZ-ADMIN-104)
 */
@Data
public class LogisticsOrderStateVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 当前物流态 in_japan / in_china_dispatching / delivered */
    private String logisticsStatus;

    /** 业务态（refunded / delivered 等，用于 E6 拦截） */
    private String businessStatus;

    /** 当前国内快递编码（改单号审计 from 用） */
    private String cnCarrierCode;

    /** 当前国内快递单号（改单号审计 from 用） */
    private String cnTrackingNo;
}
