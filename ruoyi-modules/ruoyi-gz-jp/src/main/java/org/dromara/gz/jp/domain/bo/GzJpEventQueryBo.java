package org.dromara.gz.jp.domain.bo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 场 admin 列表查询条件（GZ-JP-101，UI:admin.event）。
 *
 * <p>字段全部可选；分页走 ruoyi {@code PageQuery}（pageNum / pageSize / orderByColumn）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-JP-101)
 */
@Data
public class GzJpEventQueryBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 场编号模糊 */
    private String eventNo;

    /** 场名称模糊 */
    private String name;

    /**
     * 状态精确（draft / open / closed；空 = 全部）。
     *
     * <p>★ 按<b>生效状态</b>过滤（读时惰性）：筛 closed 时，存库 open 但 end_time 已过的场也会命中；
     * 筛 open / draft 时则排除这些已到点的场。SQL 层用 end_time 条件表达，不做内存过滤 —— 保证分页 total 准确。</p>
     */
    private String status;

    /** 开场时间区间起（含），yyyy-MM-dd HH:mm:ss */
    private String beginStartTime;

    /** 开场时间区间止（含） */
    private String endStartTime;
}
