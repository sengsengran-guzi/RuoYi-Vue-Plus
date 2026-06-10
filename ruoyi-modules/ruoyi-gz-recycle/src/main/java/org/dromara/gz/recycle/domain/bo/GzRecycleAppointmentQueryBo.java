package org.dromara.gz.recycle.domain.bo;

import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDate;

/**
 * 回收预约单 admin 列表查询条件（GZ-RECYCLE-003 AC5，按门店 / 日期 / 状态筛）。
 *
 * @author kevin-coder (sensenran-guzi · GZ-RECYCLE-003)
 */
@Data
public class GzRecycleAppointmentQueryBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 门店 id（按门店筛） */
    private Long storeId;

    /** 状态（submitted / confirmed_onsite / paying / paid / cancelled / no_show / payout_failed） */
    private String status;

    /** 预约号（精确） */
    private String appointmentNo;

    /** 预约日期起（含） */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate apptDateStart;

    /** 预约日期止（含） */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate apptDateEnd;
}
