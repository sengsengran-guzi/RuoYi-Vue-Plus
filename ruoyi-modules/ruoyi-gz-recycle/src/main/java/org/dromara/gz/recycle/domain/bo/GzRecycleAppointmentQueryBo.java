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

    /**
     * 记录来源（{@code mp} / {@code manual}，ADR-0021 §12.7）。显式传时精确按来源筛（admin 主动查手动占用）。
     *
     * <p><b>不是「显示手动记录」的默认入口</b>——AC22 口径：默认列表不含 {@code source='manual'} 行，
     * 状态筛选（{@code status=manual_hold}）才是显示手动记录的唯一默认入口；本字段仅为显式精确过滤，
     * 不影响该默认收敛逻辑（service 侧未显式传 source 时才走 status 驱动的默认排除）。</p>
     */
    private String source;

    /** 状态（submitted / confirmed_onsite / paying / paid / cancelled / no_show / payout_failed / manual_hold） */
    private String status;

    /** 预约号（精确） */
    private String appointmentNo;

    /** 预约日期起（含） */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate apptDateStart;

    /** 预约日期止（含） */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate apptDateEnd;

    /** 点数档编码（qty_bucket_code；存于 product_snapshot_json，回收看板记录区按点数档筛选，GZ-RECYCLE-008） */
    private String qtyBucketCode;

    /** 实付金额下限（分，含；按最终收款金额区间筛选） */
    private Long finalAmountCentMin;

    /** 实付金额上限（分，含） */
    private Long finalAmountCentMax;
}
