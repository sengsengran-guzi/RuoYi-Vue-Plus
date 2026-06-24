package org.dromara.gz.gacha.domain.bo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 产品库列表查询条件（ADR-0013 / GZ-GACHA-112 admin 端 /list 筛选）。
 *
 * <p>支持按 name（模糊）/ ipTag（精确）/ enabled（精确）筛选；分页走 ruoyi {@code PageQuery}。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-GACHA-112)
 */
@Data
public class GzGachaProductQueryBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 产品名模糊筛选 */
    private String name;

    /** IP 标签精确筛选 */
    private String ipTag;

    /** 是否可投放精确筛选（0/1） */
    private Integer enabled;
}
