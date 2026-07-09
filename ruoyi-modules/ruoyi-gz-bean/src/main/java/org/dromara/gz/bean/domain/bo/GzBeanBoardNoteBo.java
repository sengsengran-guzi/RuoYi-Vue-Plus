package org.dromara.gz.bean.domain.bo;

import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 看板座位备注更新 BO：店员在店内计时看板点座位记一条备注，纯挂座位（{@code gz_bean_seat.remark}）——
 * 与座位是否有人/空闲无关，店员手动填/清；<b>每天自动清理（GZ-BEAN-052：只当天有效，跨日读看板时自动清空）</b>，
 * 座位状态变化本身不触发清除。{@code remark} 传空/空串 = 清空（删除备注）。
 *
 * <p>长度上限 500 与 {@code gz_bean_seat.remark} 对齐。</p>
 */
@Data
public class GzBeanBoardNoteBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 备注内容（可空 = 清空备注；≤ 500） */
    @Size(max = 500, message = "备注长度不能超过 500")
    private String remark;
}
