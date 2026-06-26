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

    /**
     * 综合状态多选（admin 列表筛选，值 = 派生 bizStatus：paid/used/cancelled/refunded/no_show/unpaid/closed）。
     * 空 → 默认只显真实订单（pay_status∈paid,refunded）；非空 → 按所选 bizStatus 映射到 (status,pay_status) 条件。
     */
    private List<String> bizStatusList;

    /** 业务码搜索 */
    private String bookingNo;

    /** 手机号搜索 */
    private String mobile;
}
