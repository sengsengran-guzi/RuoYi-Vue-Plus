package org.dromara.gz.common.cs.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 客服配置 VO（GZ-SYS-004B）。admin 回填 + mp 读取共用，三字段恒非 null（空配置返空串）。
 *
 * @author kevin-coder (sensenran-guzi · GZ-SYS-004B)
 */
@Data
public class GzCustomerServiceConfigVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 企业微信客服账号（空 → mp 走降级弹窗） */
    private String wxKfId;

    /** 降级客服电话 */
    private String phone;

    /** 降级客服微信号 */
    private String wxId;
}
