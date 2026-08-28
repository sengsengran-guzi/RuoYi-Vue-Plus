package org.dromara.gz.recycle.domain.bo;

import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * admin 预约改期提交参数（ADR-0021 §2，原地 UPDATE，不取消重建）。
 *
 * <p>对应 {@code POST /system/gz/recycle/appointment/{id}/reschedule}。<b>不含 storeId</b>
 * ——不允许跨门店改期（换门店 = 取消重约），门店取原单 {@code store_id} 不变。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-RECYCLE-010)
 */
@Data
public class GzRecycleRescheduleBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 新到店日期 */
    @NotNull(message = "请选择新的到店日期")
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate apptDate;

    /**
     * 新到店<b>起始整点</b>（GZ-RECYCLE-012 / ADR-0022）。
     *
     * <p>不传时长 —— 本单占几小时由它自己决定（顾客单读提交时冻结的快照，手动占用取当前区间宽度），
     * 改期只换位置、不改占格面。</p>
     */
    @NotNull(message = "请选择新的到店时间")
    @DateTimeFormat(pattern = "HH:mm:ss")
    private LocalTime slotStart;
}
