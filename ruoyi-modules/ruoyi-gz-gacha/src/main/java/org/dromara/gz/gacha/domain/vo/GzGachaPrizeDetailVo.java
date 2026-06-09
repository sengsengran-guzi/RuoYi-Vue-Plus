package org.dromara.gz.gacha.domain.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;

/**
 * mp 单机详情 — 奖品池单条展示对象（GZ-GACHA-103，概率公示）。
 *
 * <p>字段口径权威：doc/11 §7.2（mp 公示子集）。<b>不暴露 weight 裸值</b>（GACHA-102 契约）：只暴露
 * 后端算好的 {@code normalizedProbability}（百分比），由 {@code ProbabilityNormalizer.normalizeToPercent}
 * 与开盒事务同口径计算，前端不重算。</p>
 *
 * <p><b>归一化口径</b>（doc/11 §7.2）：入池子集 {@code enabled=1 AND stock_remain>0} 的 weight 归一化；
 * 不在池（{@code stock_remain=0} 或 {@code enabled=0}）→ {@code normalizedProbability=null}，前端展示 "—"，
 * 不参与分母（决策 D2：售罄奖品仍返回，前端灰显「已抽完」，不后端过滤）。</p>
 *
 * <p>跨层契约（CLAUDE.md #1）：{@code id} / {@code imageId} 用 {@link ToStringSerializer} 转 string。
 * 图片不暴露裸 file_id，由后端解析为 {@code imageUrl} 可访问签名 URL（NULL → 占位图）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-GACHA-103)
 */
@Data
public class GzGachaPrizeDetailVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 奖品主键（string） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** 奖品名 */
    private String name;

    /** 奖品图可访问 URL（由 image_id 经 gz_file_object 解析；NULL / 解析失败 → 占位图） */
    private String imageUrl;

    /** 稀有度 SSR/SR/R/N（仅展示，前端配色边框 + 徽章；doc/tokens.md §1.4 四档） */
    private String rarity;

    /**
     * 实时归一化出现概率（百分比，如 {@code 12.34}）。
     *
     * <p>入池（{@code enabled=1 AND stock_remain>0}）→ 由 {@code ProbabilityNormalizer.normalizeToPercent}
     * 算 {@code weight_i / Σ weight_j × 100}（HALF_UP 2 位，与开盒事务同口径）；不在池 → {@code null}
     * （前端展示 "—"，不参与分母）。</p>
     */
    private BigDecimal normalizedProbability;

    /** 剩余库存（=0 前端灰显「已抽完」遮罩；决策 D2 不后端过滤） */
    private Integer stockRemain;

    /** 公示参考价（分，null = 不显示；前端 /100 显示元） */
    private Long referenceValueCent;

    /** 是否参与抽奖（0=临时下架 / 1=参与；前端区分「不参与」与「已抽完」） */
    private Integer enabled;
}
