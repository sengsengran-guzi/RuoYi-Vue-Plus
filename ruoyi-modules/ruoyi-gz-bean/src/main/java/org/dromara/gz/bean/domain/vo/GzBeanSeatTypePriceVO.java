package org.dromara.gz.bean.domain.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Builder;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalTime;

/**
 * 按星期 × 1h 格价格覆盖 VO（GZ-BEAN-018 → GZ-BEAN-033，ADR-0015 §3.1）。
 *
 * <p>admin 「星期 × 1h 格价格网格」配置页消费：某 config 已配的「星期 × 格」覆盖价行。
 * {@code slotStart=null} = 该星期整天默认价；{@code slotStart=HH:00:00} = 该星期该 1h 格覆盖价。
 * 未在列表中的「星期×格」= 未覆盖（下单时 3 级回退：格价 → 整天默认 → 基础价）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-BEAN-018 / GZ-BEAN-033)
 */
@Data
@Builder
public class GzBeanSeatTypePriceVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** ISO 8601 星期 1=Mon..7=Sun */
    private Integer weekday;

    /** 该 1h 格起整点：null=该星期整天默认价 / HH:mm:ss=该星期该 1h 格覆盖价（ADR-0015 §3.1） */
    @JsonFormat(pattern = "HH:mm:ss")
    private LocalTime slotStart;

    /** 覆盖单价（分；前端 /100 显示元） */
    private Long priceCent;

    /** 覆盖单价（元；service 由 priceCent /100 算，便于 admin 直接展示） */
    private BigDecimal priceYuan;
}
