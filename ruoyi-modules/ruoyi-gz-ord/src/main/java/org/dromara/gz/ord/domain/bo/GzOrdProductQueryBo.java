package org.dromara.gz.ord.domain.bo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 预购商品列表查询条件（GZ-ORD-101 admin 端，AC 2 /list 筛选）。
 *
 * <p>支持按 status（精确）/ ipTag（精确）/ name（模糊）筛选；排序 deadline_time / create_time（默认）。
 * 分页走 ruoyi {@code PageQuery}（pageNum / pageSize / orderByColumn / isAsc）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-ORD-101)
 */
@Data
public class GzOrdProductQueryBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 商品名模糊筛选 */
    private String name;

    /** 状态精确筛选（on_shelf/off_shelf/auto_off） */
    private String status;

    /** IP 标签精确筛选 */
    private String ipTag;

    /** 截止日范围起（含；yyyy-MM-dd，GZ-ADMIN-101 AC 2 按 deadline_time 过滤） */
    private String deadlineStart;

    /** 截止日范围止（含当日 23:59:59；yyyy-MM-dd） */
    private String deadlineEnd;
}
