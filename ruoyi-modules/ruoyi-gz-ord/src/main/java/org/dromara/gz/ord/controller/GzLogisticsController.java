package org.dromara.gz.ord.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.dromara.common.web.core.BaseController;
import org.dromara.gz.ord.domain.dto.LogisticsCarrierUpdateDto;
import org.dromara.gz.ord.domain.dto.LogisticsForwardDto;
import org.dromara.gz.ord.domain.dto.LogisticsRollbackDto;
import org.dromara.gz.ord.service.IGzLogisticsService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * GZ-ADMIN-104 跨境物流 2 态推进（plus-ui owner 兜底端）。
 *
 * <p>路径 {@code /system/gz/ord/logistics}。owner 电脑后台推进 / 改单号 / 回退（回退仅 owner）；
 * 与 mp 店员端共用 {@link IGzLogisticsService}。权限复用 ADR-0004 RBAC（@SaCheckPermission，同租户 1001）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-ADMIN-104)
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/system/gz/ord/logistics")
public class GzLogisticsController extends BaseController {

    private final IGzLogisticsService logisticsService;

    /** 推进到下一态（in_japan→in_china_dispatching 必录快递+单号 / in_china_dispatching→delivered）。 */
    @Log(title = "跨境物流推进", businessType = BusinessType.UPDATE)
    @SaCheckPermission("gz:ord:logistics:push")
    @PostMapping("/forward")
    public R<Void> forward(@Validated @RequestBody LogisticsForwardDto dto) {
        logisticsService.forward(dto);
        return R.ok();
    }

    /** 改单号（仅 in_china_dispatching，不改状态）。 */
    @Log(title = "跨境物流改单号", businessType = BusinessType.UPDATE)
    @SaCheckPermission("gz:ord:logistics:push")
    @PutMapping("/carrier")
    public R<Void> updateCarrier(@Validated @RequestBody LogisticsCarrierUpdateDto dto) {
        logisticsService.updateCarrier(dto);
        return R.ok();
    }

    /** owner 回退（reason 必填，仅 owner 权限）。 */
    @Log(title = "跨境物流回退", businessType = BusinessType.UPDATE)
    @SaCheckPermission("gz:ord:logistics:rollback")
    @PostMapping("/rollback")
    public R<Void> rollback(@Validated @RequestBody LogisticsRollbackDto dto) {
        logisticsService.rollback(dto);
        return R.ok();
    }
}
