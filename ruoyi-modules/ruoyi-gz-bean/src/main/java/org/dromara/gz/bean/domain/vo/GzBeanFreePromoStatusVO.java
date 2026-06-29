package org.dromara.gz.bean.domain.vo;

import lombok.Builder;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 前 N 名免费促销 mp 提示状态 VO（GZ-BEAN-025，ADR-0015 §4 / doc/11 §3.11）。
 *
 * <p>{@code GET /app/gz/bean/free-promo/status?storeId} 返回。{@code remaining>0} → mp 显眼 banner
 * 「本店前 N 名免费，还剩 X 名」+ 选座页价格区标「本单免费」；{@code remaining<=0} 或促销关
 * （{@code enabled=false}）→ 不显 banner、显真实价格。</p>
 *
 * <p>「还剩 X 名」是促销话术（引流紧迫感数字），与座位 full 布尔无关、不违反余量铁律（doc/11 §3.11）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-BEAN-025)
 */
@Data
@Builder
public class GzBeanFreePromoStatusVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 促销是否生效（enabled=1 且当天在 [start_date, end_date] 窗口内）；false → mp 不显 banner、显真实价格 */
    private Boolean enabled;

    /** 每周期免费名额 N（enabled=false 时为 0） */
    private Integer freeCount;

    /** 本周期剩余免费名额 = free_count − 桶内已发（下限 0）；>0 才显 banner */
    private Integer remaining;

    /** 周期文案：「今日」(day) / 「本周」(week) / 「本周期」(days)；enabled=false 时为 null */
    private String periodLabel;
}
