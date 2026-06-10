package org.dromara.gz.recon.domain.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import org.dromara.gz.recon.domain.entity.GzReconMonthly;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * gz_recon_monthly 视图对象（GZ-ADMIN-105，admin 月度对账单列表 / 明细）。
 *
 * <p>字段权威：doc/11 §9.2。金额保持 cent（Long），前端 / 100 显示元；
 * commissionRateBp 前端按 / 100 显示百分比（400 → 4%）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-ADMIN-105)
 */
@Data
@AutoMapper(target = GzReconMonthly.class)
public class GzReconMonthlyVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    private String businessMonth;

    private String businessType;

    private Long gmvCent;

    private Long refundCent;

    private Long channelFeeCent;

    private Long settleCent;

    private Integer commissionRateBp;

    private Long commissionCent;

    private String status;

    private String confirmedBy;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime confirmedTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime settledTime;
}
