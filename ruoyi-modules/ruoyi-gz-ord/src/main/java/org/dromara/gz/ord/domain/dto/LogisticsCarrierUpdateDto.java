package org.dromara.gz.ord.domain.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 改单号入参（GZ-ADMIN-104，doc/10 §9.N9 / E2，不改 logistics_status / 不重置 cn_dispatched_at）。
 *
 * @author kevin-coder (sensenran-guzi · GZ-ADMIN-104)
 */
@Data
public class LogisticsCarrierUpdateDto implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @NotBlank(message = "业务类型不能为空")
    private String businessType;

    @NotBlank(message = "订单号不能为空")
    private String businessOrderNo;

    @NotBlank(message = "快递公司不能为空")
    private String cnCarrierCode;

    @NotBlank(message = "快递单号不能为空")
    private String cnTrackingNo;
}
