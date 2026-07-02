package org.dromara.gz.bean.domain.bo;

import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 看板备注更新 BO：店员在店内计时看板点座位记一条备注。按占用状态分两处存储 ——
 * 座位占用中传 {@code bookingId}（挂本次占用单 board_note，放座后不再展示）；座位空闲不传 bookingId
 * （挂 gz_bean_seat.remark，长期留存）。{@code remark} 传空/空串 = 清空（删除备注）。
 *
 * <p>长度上限 500 与 {@code gz_bean_seat.remark} / {@code gz_bean_booking.board_note} 对齐。</p>
 */
@Data
public class GzBeanBoardNoteBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 本次占用单 id（座位占用中记本次备注时传；为空则记座位备注） */
    private Long bookingId;

    /** 备注内容（可空 = 清空备注；≤ 500） */
    @Size(max = 500, message = "备注长度不能超过 500")
    private String remark;
}
