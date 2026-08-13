package org.dromara.gz.bean.domain.vo;

import lombok.Builder;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 包天套餐按星期价 VO（GZ-BEAN-053）。
 *
 * <p>admin 「星期价格网格」配置页的「包天」列消费：某 config 已配的星期包天覆盖价行。
 * 未在列表中的星期 = 未覆盖（下单时回退 config.day_pass_price_cent 基础包天价）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-BEAN-053)
 */
@Data
@Builder
public class GzBeanDayPassPriceVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** ISO 8601 星期 1=Mon..7=Sun */
    private Integer weekday;

    /** 该星期的包天固定价（分；前端 /100 显示元） */
    private Long priceCent;

    /** 该星期的包天固定价（元；service 由 priceCent /100 算，便于 admin 直接展示） */
    private BigDecimal priceYuan;
}
