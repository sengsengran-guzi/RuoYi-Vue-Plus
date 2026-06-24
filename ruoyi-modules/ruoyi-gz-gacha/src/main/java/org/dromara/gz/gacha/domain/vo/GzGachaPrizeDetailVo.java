package org.dromara.gz.gacha.domain.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * mp 单机详情 — 奖品池单条展示对象（GZ-GACHA-103，ADR-0013 去概率公示）。
 *
 * <p>字段口径权威：doc/11 §7.2（mp 公示子集）。<b>ADR-0013 决策（甲方拍板）</b>：mp 单机详情<b>不显示概率</b> ——
 * weight 仅后台驱动抽奖，不对 C 端展示任何概率百分比；故本 VO <b>移除 normalizedProbability 字段</b>。
 * 名 / 图 / 参考价取产品库（join），稀有度取投放线。</p>
 *
 * <p>跨层契约（CLAUDE.md #1）：{@code id} 用 {@link ToStringSerializer} 转 string。图片不暴露裸 file_id，
 * 由后端解析为 {@code imageUrl} 可访问签名 URL（NULL → 占位图）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-GACHA-103 / ADR-0013)
 */
@Data
public class GzGachaPrizeDetailVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 投放线主键（string） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** 产品名（join 产品库回填） */
    private String name;

    /** 产品图可访问 URL（由产品 image_id 经 gz_file_object 解析；NULL / 解析失败 → 占位图） */
    private String imageUrl;

    /** 稀有度 SSR/SR/R/N（按机器可调，取投放线；前端配色边框 + 徽章；doc/tokens.md §1.4 四档） */
    private String rarity;

    /** 剩余库存（=0 前端灰显「已抽完」遮罩；决策 D2 不后端过滤） */
    private Integer stockRemain;

    /** 公示参考价（分，null = 不显示；join 产品库回填；前端 /100 显示元） */
    private Long referenceValueCent;

    /** 是否参与抽奖（0=临时下架 / 1=参与；前端区分「不参与」与「已抽完」） */
    private Integer enabled;
}
