package org.dromara.gz.gacha.domain.bo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 扭蛋机列表查询条件（GZ-GACHA-101 admin 端 /list 筛选）。
 *
 * <p>支持按 status（精确）/ ipTag（精确）/ name（模糊）筛选；分页走 ruoyi {@code PageQuery}。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-GACHA-101)
 */
@Data
public class GzGachaMachineQueryBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 机器名模糊筛选 */
    private String name;

    /** 状态精确筛选（on_shelf/off_shelf/auto_off） */
    private String status;

    /** IP 标签精确筛选 */
    private String ipTag;
}
