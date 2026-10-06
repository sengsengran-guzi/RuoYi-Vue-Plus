package org.dromara.gz.jp.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.common.tenant.helper.TenantHelper;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.dromara.gz.jp.service.internal.GzJpSnapConfigService;
import org.springframework.web.bind.annotation.*;

@RestController @RequiredArgsConstructor @RequestMapping("/system/gz/jp/snap/config")
public class GzJpSnapConfigController {
    private final GzJpSnapConfigService service;
    private void tenant(){if(!"1001".equals(TenantHelper.getTenantId()))throw new ServiceException("仅业务租户可操作 / Business tenant required");}
    @GetMapping("/{key}") @SaCheckPermission("gz:jp:snap:config:query")
    public R<JsonNode> read(@PathVariable String key){tenant();return R.ok(service.read(key));}
    @PutMapping("/{key}") @SaCheckPermission("gz:jp:snap:config:edit")
    @Log(title="代切配置",businessType=BusinessType.UPDATE)
    public R<Void> save(@PathVariable String key,@RequestBody JsonNode input){tenant();service.save(key,input,LoginHelper.getUserId());return R.ok();}
}
