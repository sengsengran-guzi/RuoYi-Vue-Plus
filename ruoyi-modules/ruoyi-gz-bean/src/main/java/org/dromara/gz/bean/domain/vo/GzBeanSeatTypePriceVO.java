package org.dromara.gz.bean.domain.vo;

import lombok.Builder;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 按星期价格覆盖 VO（GZ-BEAN-018，ADR-0014 §3）。
 *
 * <p>admin 周价格配置页消费：某 config 已配的某星期覆盖价。未在列表中的星期 = 未覆盖（回退基础价）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-BEAN-018)
 */
@Data
@Builder
public class GzBeanSeatTypePriceVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** ISO 8601 星期 1=Mon..7=Sun */
    private Integer weekday;

    /** 覆盖单价（分；前端 /100 显示元） */
    private Long priceCent;

    /** 覆盖单价（元；service 由 priceCent /100 算，便于 admin 直接展示） */
    private BigDecimal priceYuan;
}
