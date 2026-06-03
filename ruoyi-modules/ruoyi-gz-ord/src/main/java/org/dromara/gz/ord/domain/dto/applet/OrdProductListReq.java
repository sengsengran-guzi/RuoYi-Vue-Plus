package org.dromara.gz.ord.domain.dto.applet;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * mp 预购列表查询入参（GZ-ORD-102 AC1）。
 *
 * <p>分页 + 排序 + IP 多选筛选。{@code sortBy} 非法值由 {@link org.dromara.gz.ord.enums.OrdProductSortEnum#ofCodeOrDefault}
 * 兜底；{@code ipTags} 为逗号分隔字符串（前端拼好传），service 层 split + 最多取前 10 个（强约束 R5）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-ORD-102)
 */
@Data
public class OrdProductListReq implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 页码（从 1，默认 1） */
    private Integer pageNum = 1;

    /** 每页条数（默认 20） */
    private Integer pageSize = 20;

    /** 排序 code：arrival_asc / hot / deadline_asc（默认 deadline_asc，非法兜底） */
    private String sortBy;

    /** IP 标签筛选（逗号分隔；空 = 不筛；service 最多取前 10 个） */
    private String ipTags;
}
