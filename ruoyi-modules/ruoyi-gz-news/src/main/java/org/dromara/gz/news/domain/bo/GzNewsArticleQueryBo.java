package org.dromara.gz.news.domain.bo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 资讯文章 admin 列表查询条件（GZ-NEWS-003 AC5）。
 *
 * <p>所有字段可选；分页参数走 ruoyi {@code PageQuery}（pageNum / pageSize / orderByColumn）。
 * 时间范围用 createTime 区间（beginCreateTime / endCreateTime，ruoyi {@code Between} 习惯）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-NEWS-003)
 */
@Data
public class GzNewsArticleQueryBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 标题模糊 */
    private String title;

    /** 分类 code 精确 */
    private String categoryCode;

    /** 状态精确（draft/scheduled/published/offline；空=全部） */
    private String status;

    /** 创建时间区间起（含），格式 yyyy-MM-dd HH:mm:ss */
    private String beginCreateTime;

    /** 创建时间区间止（含） */
    private String endCreateTime;
}
