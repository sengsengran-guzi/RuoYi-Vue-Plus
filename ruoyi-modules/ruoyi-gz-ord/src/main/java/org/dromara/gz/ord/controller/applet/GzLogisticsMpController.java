package org.dromara.gz.ord.controller.applet;

import cn.dev33.satoken.annotation.SaCheckPermission;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.dromara.gz.ord.domain.dto.LogisticsCarrierUpdateDto;
import org.dromara.gz.ord.domain.dto.LogisticsForwardDto;
import org.dromara.gz.ord.service.IGzLogisticsService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * GZ-ADMIN-104 跨境物流推进（mp 店员端主形态）。
 *
 * <p>路径 {@code /app/gz/staff/logistics}（对齐既有 {@code /app/gz/staff/orders} 店员端约定）。
 * 店员手机现场发货录单 = 高频主形态；复用 ADR-0004 权限底座（openid 绑 sys_user + @SaCheckPermission
 * + 同租户 1001 + 停用/解绑即时踢）。<b>回退仅 owner</b>（plus-ui），mp 店员端无回退入口。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-ADMIN-104)
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/app/gz/staff/logistics")
public class GzLogisticsMpController {

    private final IGzLogisticsService logisticsService;

    /** 推进到下一态（店员现场发货：发往中国录单号 / 推到已签收）。 */
    @Log(title = "店员物流推进", businessType = BusinessType.UPDATE)
    @SaCheckPermission("gz:ord:logistics:push")
    @PostMapping("/forward")
    public R<Void> forward(@Validated @RequestBody LogisticsForwardDto dto) {
        logisticsService.forward(dto);
        return R.ok();
    }

    /** 改单号（店员录错单号二次修正）。 */
    @Log(title = "店员物流改单号", businessType = BusinessType.UPDATE)
    @SaCheckPermission("gz:ord:logistics:push")
    @PutMapping("/carrier")
    public R<Void> updateCarrier(@Validated @RequestBody LogisticsCarrierUpdateDto dto) {
        logisticsService.updateCarrier(dto);
        return R.ok();
    }
}
