package org.dromara.gz.recycle.domain.bo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 回收 IP 主数据列表查询对象（GZ-RECYCLE-004 admin 端）。
 *
 * @author kevin-coder (sensenran-guzi · GZ-RECYCLE-004)
 */
@Data
public class GzRecycleIpQueryBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** IP 名称模糊（LIKE） */
    private String ipName;

    /** 启用标志精确（0=停用 / 1=启用） */
    private Integer enabled;
}
