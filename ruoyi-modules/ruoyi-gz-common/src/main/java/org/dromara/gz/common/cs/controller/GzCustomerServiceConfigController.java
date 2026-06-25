package org.dromara.gz.common.cs.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.dromara.common.web.core.BaseController;
import org.dromara.gz.common.cs.domain.bo.GzCustomerServiceConfigBo;
import org.dromara.gz.common.cs.domain.vo.GzCustomerServiceConfigVO;
import org.dromara.gz.common.cs.service.IGzCustomerServiceConfigService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 客服配置 admin 端（GZ-SYS-004B）。
 *
 * <p>取代原直接调 ruoyi {@code /system/config}（要 system:config 权限、按租户隔离）。本端点用
 * gz 自有权限 {@code gz:config:cs:edit}（已绑甲方 owner 角色，菜单 5021），读写当前登录租户那一行
 * （sa-token 登录态 → TenantHelper 自动锁定租户，owner 即租户 1001）。前缀 {@code /system/gz/customerService}。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-SYS-004B)
 */
@Validated
@RestController
@RequestMapping("/system/gz/customerService")
@RequiredArgsConstructor
public class GzCustomerServiceConfigController extends BaseController {

    private final IGzCustomerServiceConfigService configService;

    /** 读当前租户客服配置（编辑页回填）。 */
    @SaCheckPermission("gz:config:cs:edit")
    @GetMapping
    public R<GzCustomerServiceConfigVO> getConfig() {
        return R.ok(configService.getConfig());
    }

    /** 保存当前租户客服配置（upsert）。 */
    @SaCheckPermission("gz:config:cs:edit")
    @Log(title = "客服配置", businessType = BusinessType.UPDATE)
    @PutMapping
    public R<Void> save(@Valid @RequestBody GzCustomerServiceConfigBo bo) {
        configService.saveConfig(bo);
        return R.ok();
    }
}
