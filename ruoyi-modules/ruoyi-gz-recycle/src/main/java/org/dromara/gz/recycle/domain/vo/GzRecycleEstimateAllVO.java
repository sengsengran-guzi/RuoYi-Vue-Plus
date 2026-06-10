package org.dromara.gz.recycle.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 多品类累加估价结果（GZ-RECYCLE-002，doc/11 F12.1 / doc/10 §13.N3）。
 *
 * <p>对各品类 {@code (category, qty)} 调单品类 {@code estimate} 后累加：
 * {@code estimatedAmountCent = Σ 各 estimatedAmountCent}；{@code totalQty = Σ qty}；
 * {@code matchedDurationMinutes = Σ 各命中 duration_minutes}（多品类核对累计时长口径）。</p>
 *
 * <p><b>E1 分支</b>（doc/10 §13.E1）：某品类未命中价目区间 → 该品类 {@code lines[i].priced=false}（estimate 部分置 null），
 * 不阻断其余品类估价；{@code hasUnpriced=true} 时整单不可提交（mp 提示「该品类暂不支持线上估价，请到店咨询」）。
 * 累加金额 / 时长仅计已估价品类。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-RECYCLE-002)
 */
@Data
public class GzRecycleEstimateAllVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 总件数 = Σ 各品类 qty（含未估价品类的数量） */
    private Integer totalQty;

    /** 累加估价金额（分）= Σ 已估价品类 estimatedAmountCent */
    private Long estimatedAmountCent;

    /** 累加匹配时长（分钟）= Σ 已估价品类命中 duration_minutes */
    private Integer matchedDurationMinutes;

    /** 是否含未命中价目区间的品类（E1）；true → 整单不可提交，mp 提示到店咨询 */
    private Boolean hasUnpriced;

    /** 逐品类估价明细 */
    private List<EstimateLine> lines;

    /**
     * 单品类估价行（命中 → priced=true 带金额；未命中 E1 → priced=false）。
     */
    @Data
    public static class EstimateLine implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        /** 回收品类 */
        private String category;

        /** 数量 */
        private Integer qty;

        /** 是否命中价目区间（false = E1 未估价，需到店咨询） */
        private Boolean priced;

        /** 该品类单价（分/件）；priced=false 时 null */
        private Long unitPriceCent;

        /** 该品类估价（分）= unitPriceCent × qty；priced=false 时 null */
        private Long estimatedAmountCent;

        /** 该品类命中时长（分钟）；priced=false 时 null */
        private Integer matchedDurationMinutes;
    }
}
