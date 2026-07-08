package org.dromara.gz.bean.domain.bo;

import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDate;

/**
 * 拼豆营业额明细查询参数（区间下钻，只统计拼豆）。
 *
 * <p>时间范围二选一：优先 {@code startDate}/{@code endDate} 区间；缺区间则回退到单日 {@code date}
 * （service 校验至少给一种）。{@code storeId} 可选（null = 全部门店，owner 视角；staff 由 controller 强制本店）。</p>
 *
 * @author kevin-coder (sensenran-guzi · 拼豆营业额)
 */
@Data
public class GzBeanRevenueQueryBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 门店 id（可选；owner 传空 = 全部门店，staff 忽略本字段强制本店） */
    private Long storeId;

    /** 单日服务日（区间未给时回退用；与 startDate/endDate 二选一） */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate date;

    /** 区间起（服务日，含；与 date 二选一，优先区间） */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate startDate;

    /** 区间止（服务日，含；与 date 二选一，优先区间） */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate endDate;

    /** 支付方式筛选（明细用）：cash / online / null=全部 */
    private String payMethod;

    /** 桌型筛选（明细用）：seat_type code（single/double/quad/自定义 st&lt;id&gt;/unknown）；null=全部桌型 */
    private String seatType;
}
