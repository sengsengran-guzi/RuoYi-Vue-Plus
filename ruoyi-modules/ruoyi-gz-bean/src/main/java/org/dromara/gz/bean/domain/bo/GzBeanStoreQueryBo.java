package org.dromara.gz.bean.domain.bo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * gz_bean_store admin 列表查询条件（GZ-BEAN-001）。
 *
 * <p>admin 端 GET /system/gz/bean/store/list 接收的查询参数：
 * name 模糊；storeNo 等值；type / status 等值。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-BEAN-001)
 */
@Data
public class GzBeanStoreQueryBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 业务码（精确） */
    private String storeNo;

    /** 门店名（模糊） */
    private String name;

    /** 类型 pindou / guzi */
    private String type;

    /** 状态 open / closed / maintenance */
    private String status;
}
