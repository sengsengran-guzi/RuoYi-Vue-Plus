package org.dromara.gz.bean.domain.bo;

import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDate;
import java.util.List;

/**
 * admin 端预约查询参数（GZ-BEAN-004）。
 *
 * @author kevin-coder (sensenran-guzi · GZ-BEAN-004)
 */
@Data
public class GzBeanBookingQueryBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 门店 ID（staff 角色应只看自己门店；本 ticket V1.0 仅一家不强约束） */
    private Long storeId;

    /** 预约日期起 */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate sessDateFrom;

    /** 预约日期止 */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate sessDateTo;

    /** 状态 pending / used / cancelled / no_show（单值，兼容旧调用） */
    private String status;

    /** 状态多选（GZ-BEAN-008 admin 列表筛选）；非空时 IN (...)，优先于 status 单值 */
    private List<String> statusList;

    /** 支付状态多选（unpaid/paying/paid/pay_closed/refunded）；非空时 IN (...) */
    private List<String> payStatusList;

    /** 业务码搜索 */
    private String bookingNo;

    /** 手机号搜索 */
    private String mobile;
}
