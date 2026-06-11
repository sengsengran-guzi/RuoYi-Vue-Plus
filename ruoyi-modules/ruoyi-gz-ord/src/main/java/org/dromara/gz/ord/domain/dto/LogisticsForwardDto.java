package org.dromara.gz.ord.domain.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 物流推进入参（GZ-ADMIN-104，in_japan→in_china_dispatching→delivered）。
 *
 * <p>进 in_china_dispatching 必带 cnCarrierCode（9 项字典）+ cnTrackingNo（service 按当前态校验，
 * doc/10 §9.N2 / E5）；进 delivered 不需快递信息。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-ADMIN-104)
 */
@Data
public class LogisticsForwardDto implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 业务线 preorder / gacha */
    @NotBlank(message = "业务类型不能为空")
    private String businessType;

    /** 业务订单号（gz_ord_order/gz_gacha_order.order_no） */
    @NotBlank(message = "订单号不能为空")
    private String businessOrderNo;

    /** 国内快递编码（进 in_china_dispatching 必录，9 项字典） */
    private String cnCarrierCode;

    /** 国内快递单号（进 in_china_dispatching 必录） */
    private String cnTrackingNo;
}
