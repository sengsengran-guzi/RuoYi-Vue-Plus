package org.dromara.gz.bean.domain.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Builder;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;

/**
 * mp 包天可用性 VO（GZ-BEAN-042 / ADR-0017）。
 *
 * <p>某门店某日各<b>开放包天的桌型档</b>（{@code day_pass_quota > 0} 且启用）的包天套餐可选状态：固定价 + 是否售罄。
 * 对应 {@code GET /app/gz/bean/booking/day-pass-options}。</p>
 *
 * <p><b>铁律 — 不向 UI 暴露余量数字</b>（对齐 type-slots）：后端内部按 {@code day_pass_quota − 已售包天} 算 {@code full}，
 * 只把布尔 {@code full} 给 mp（不下发剩余名额数字）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-BEAN-042)
 */
@Data
@Builder
public class GzBeanDayPassOptionVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 桌型档 id（mp 提交包天时回传后端；string 防 JS 精度丢失） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long seatTypeConfigId;

    /** 座位类型自定义显示名（config.name；mp 卡标题） */
    private String name;

    /** 订法 whole=整桌 / seat=按座（mp 显示标签） */
    private String bookMode;

    /** 包天固定价（分；mp /100 显示元） */
    private Long dayPassPriceCent;

    /** 包天固定价（元；便于 mp 直接展示） */
    private BigDecimal dayPassPriceYuan;

    /** 当日该桌型包天是否售罄（已售 ≥ day_pass_quota）；true → mp 灰显「已满」不可点 */
    private Boolean full;
}
