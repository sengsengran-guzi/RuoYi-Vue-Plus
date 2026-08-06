package org.dromara.gz.jp.domain.bo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 商品 admin 列表查询条件（GZ-JP-102，UI:admin.product「标准 ruoyi CRUD，顶部按场筛选」）。
 *
 * <p>字段全部可选；分页走 ruoyi {@code PageQuery}（pageNum / pageSize）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-JP-102)
 */
@Data
public class GzJpProductQueryBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 所属场精确筛选（UI:admin.product 顶部「按场筛选」；空 = 全部场） */
    private Long eventId;

    /** 商品编号模糊 */
    private String productNo;

    /** 商品名称模糊 */
    private String name;

    /** 商品状态精确（on_shelf / off_shelf；空 = 全部）—— 未知值忽略该条件，不误当 off_shelf 过滤 */
    private String status;
}
