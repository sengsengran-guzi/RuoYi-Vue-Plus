package org.dromara.gz.common.cs.service;

import org.dromara.gz.common.cs.domain.bo.GzCustomerServiceConfigBo;
import org.dromara.gz.common.cs.domain.vo.GzCustomerServiceConfigVO;

/**
 * 客服配置服务（GZ-SYS-004B，按租户单行）。
 *
 * <p>admin 读/存（owner 权限）；mp 按当前登录租户读。每租户单行，存即 upsert。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-SYS-004B)
 */
public interface IGzCustomerServiceConfigService {

    /**
     * 读当前租户客服配置；无行则返三字段空串的 VO（不返 null，mp/admin 直接用）。
     */
    GzCustomerServiceConfigVO getConfig();

    /**
     * 保存当前租户客服配置（存在则更新，不存在则新建；按 tenant_id 单行 upsert）。
     */
    void saveConfig(GzCustomerServiceConfigBo bo);
}
