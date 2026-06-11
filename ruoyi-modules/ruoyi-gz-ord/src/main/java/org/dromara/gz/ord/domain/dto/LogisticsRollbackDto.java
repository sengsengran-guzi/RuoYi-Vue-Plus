package org.dromara.gz.ord.domain.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * owner 回退入参（GZ-ADMIN-104，doc/10 §9.N8，reason 必填，仅 owner 权限）。
 *
 * @author kevin-coder (sensenran-guzi · GZ-ADMIN-104)
 */
@Data
public class LogisticsRollbackDto implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @NotBlank(message = "业务类型不能为空")
    private String businessType;

    @NotBlank(message = "订单号不能为空")
    private String businessOrderNo;

    /** 回退原因（必填，留痕审计） */
    @NotBlank(message = "回退原因不能为空")
    private String reason;
}
