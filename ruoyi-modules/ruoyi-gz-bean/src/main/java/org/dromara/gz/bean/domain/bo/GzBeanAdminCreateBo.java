package org.dromara.gz.bean.domain.bo;

import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * admin 代客预定参数（GZ-BEAN-039 / kevin-test §4）。
 *
 * <p>对应 {@code POST /system/gz/bean/booking/admin-create}。现场没带手机的用户，店员代为锁座：
 * 选门店 / 日期 / 连续 1h 区间 / 桌型档 / <b>具体座位</b>，一步生成 {@code used + pay_status=paid}
 * （线下已付）的预约。仍走逐格配额防超卖 + 具体座位区间互斥（同核销分座）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-BEAN-039)
 */
@Data
public class GzBeanAdminCreateBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 门店 ID */
    @NotNull
    private Long storeId;

    /** 桌型档 id（gz_bean_seat_type_config.id；防超卖按该档逐格配额计数 + 计价来源） */
    @NotNull
    private Long seatTypeConfigId;

    /** 预约日期（yyyy-MM-dd） */
    @NotNull
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate sessDate;

    /** 区间起（整点，含） */
    @NotNull
    @DateTimeFormat(pattern = "HH:mm:ss")
    private LocalTime slotStart;

    /** 区间止（整点，不含） */
    @NotNull
    @DateTimeFormat(pattern = "HH:mm:ss")
    private LocalTime slotEnd;

    /** 顾客手机号（选填）：命中既有 gz_user 则关联到该用户，否则用门店租户级「线下散客」占位用户 */
    private String mobile;

    /** 顾客姓名 / 备注（选填，落 booking_log 与 remark 便于店员辨识） */
    private String customerName;

    /**
     * 线下收款金额（分，选填）：默认按区间逐格求和计价（与 mp 同口径），入参非空则覆盖
     * （店员现场议价 / 抹零等）。下限 0。
     */
    private Long amountCent;
}
