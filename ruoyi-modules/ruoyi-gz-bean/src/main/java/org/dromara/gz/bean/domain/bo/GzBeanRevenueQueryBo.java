package org.dromara.gz.bean.domain.bo;

import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDate;

/**
 * 拼豆营业额查询参数（按天，只统计拼豆）。
 *
 * <p>{@code date} 必填（单日口径）；{@code storeId} 可选（null = 全部门店，owner 视角；staff 由 controller 强制本店）。</p>
 *
 * @author kevin-coder (sensenran-guzi · 拼豆营业额)
 */
@Data
public class GzBeanRevenueQueryBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 门店 id（可选；owner 传空 = 全部门店，staff 忽略本字段强制本店） */
    private Long storeId;

    /** 服务日（必填，单日汇总口径） */
    @NotNull(message = "查询日期不能为空")
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate date;

    /** 支付方式筛选（明细用）：cash / online / null=全部 */
    private String payMethod;
}
