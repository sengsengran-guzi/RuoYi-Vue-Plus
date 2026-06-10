package org.dromara.gz.recon.domain.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import org.dromara.gz.recon.domain.entity.GzReconSettle;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * gz_recon_settle 视图对象（GZ-ADMIN-105，admin 季度结算列表）。
 *
 * <p>字段权威：doc/11 §9.4。金额保持 cent（Long），前端 / 100 显示元。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-ADMIN-105)
 */
@Data
@AutoMapper(target = GzReconSettle.class)
public class GzReconSettleVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    private String quarter;

    private Long commissionTotalCent;

    private Long maintenanceTotalCent;

    private Long payableTotalCent;

    private Long paidAmountCent;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime paidTime;

    private String invoiceNo;

    private Long invoiceAmountCent;

    private String status;
}
