package org.dromara.gz.recycle.domain.bo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 回收价目表列表查询对象（GZ-RECYCLE-001 admin 端）。
 *
 * @author kevin-coder (sensenran-guzi · GZ-RECYCLE-001)
 */
@Data
public class GzRecyclePriceRuleQueryBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 回收品类精确（字典 gz_recycle_category 值） */
    private String category;

    /** 启用标志精确（0=停用 / 1=启用） */
    private Integer enabled;
}
