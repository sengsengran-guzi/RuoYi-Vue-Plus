package org.dromara.gz.common.cs.domain.bo;

import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 客服配置保存业务对象（GZ-SYS-004B admin 端）。
 *
 * <p>三字段全可空（运营按需填，全空则 mp 客服浮层走"暂未配置"兜底）。系统管理字段
 * （id / tenantId / 公共字段）不接收前端，按当前租户 upsert（每租户单行）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-SYS-004B)
 */
@Data
public class GzCustomerServiceConfigBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 企业微信客服账号（有值 → mp 渲染 contact button） */
    @Size(max = 64, message = "企业微信客服账号长度不能超过 64")
    private String wxKfId;

    /** 降级客服电话 */
    @Size(max = 32, message = "客服电话长度不能超过 32")
    private String phone;

    /** 降级客服微信号 */
    @Size(max = 64, message = "客服微信号长度不能超过 64")
    private String wxId;
}
