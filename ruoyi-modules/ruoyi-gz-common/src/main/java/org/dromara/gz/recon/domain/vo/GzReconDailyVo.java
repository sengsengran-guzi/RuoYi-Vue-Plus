package org.dromara.gz.recon.domain.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import org.dromara.gz.recon.domain.entity.GzReconDaily;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * gz_recon_daily 视图对象（GZ-ADMIN-105，admin 每日对账明细列表）。
 *
 * <p>字段权威：doc/11 §9.1。金额保持 cent（Long），前端 / 100 显示元。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-ADMIN-105)
 */
@Data
@AutoMapper(target = GzReconDaily.class)
public class GzReconDailyVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate businessDay;

    private String businessType;

    private Long systemGmvCent;

    private Long systemRefundCent;

    private Long systemFeeCent;

    private Long systemSettleCent;

    private Long channelGmvCent;

    private Long channelFeeCent;

    private Long diffGmvCent;

    private Long diffFeeCent;

    private String status;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;
}
