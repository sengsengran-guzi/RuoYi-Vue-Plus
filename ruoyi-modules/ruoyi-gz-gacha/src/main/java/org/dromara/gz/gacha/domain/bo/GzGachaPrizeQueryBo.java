package org.dromara.gz.gacha.domain.bo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 奖品列表查询条件（GZ-GACHA-101 admin 端 /list 筛选）。
 *
 * <p>核心：按 {@code machineId} 过滤（查某机器奖品池，AC 4）；可选 rarity（精确）/ enabled（精确）/
 * name（模糊）。分页走 ruoyi {@code PageQuery}。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-GACHA-101)
 */
@Data
public class GzGachaPrizeQueryBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 归属机器 id（查某机器奖品池；为空则跨机器查全部，admin 一般带 machineId） */
    private Long machineId;

    /** 奖品名模糊筛选 */
    private String name;

    /** 稀有度精确筛选（SSR/SR/R/N） */
    private String rarity;

    /** 是否参与抽奖精确筛选（0/1） */
    private Integer enabled;
}
