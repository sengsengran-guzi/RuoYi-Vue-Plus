package org.dromara.gz.recycle.domain.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 单品类估价命中结果（GZ-RECYCLE-001 AC 3，D14 RECYCLE-002 mp 填单消费）。
 *
 * <p>命中口径（doc/10 §13 估价口径 + doc/11 §12.1 末段）：按 {@code (category, qty)} 命中
 * {@code qty_min <= qty AND (qty_max IS NULL OR qty <= qty_max)} 的唯一启用规则，
 * 估价 {@code estimatedAmountCent = unitPriceCent × qty}，时长按命中规则 {@code matchedDurationMinutes} 冻结。</p>
 *
 * <p>命中 0 → service 抛「无报价规则」；命中多（区间配置错）→ 抛「配置有误」。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-RECYCLE-001)
 */
@Data
public class GzRecycleEstimateVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 命中的价目表规则主键（序列化为 string） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long ruleId;

    /** 回收品类 */
    private String category;

    /** 估价数量（入参回显） */
    private Integer qty;

    /** 命中规则单价（分/件） */
    private Long unitPriceCent;

    /** 估价金额（分）= unitPriceCent × qty */
    private Long estimatedAmountCent;

    /** 命中规则匹配时长（分钟），提交时冻结进预约单 matched_duration_minutes */
    private Integer matchedDurationMinutes;
}
