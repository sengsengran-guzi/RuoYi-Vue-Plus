package org.dromara.gz.jp.controller;
import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.idempotent.annotation.RepeatSubmit;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.dromara.gz.jp.domain.entity.GzJpFxRate;
import org.dromara.gz.jp.service.internal.GzJpSnapFxService;
import org.springframework.web.bind.annotation.*;
import java.math.BigDecimal;
import java.util.List;
@RestController @RequiredArgsConstructor @RequestMapping("/system/gz/jp/snap/fx")
public class GzJpSnapFxController extends org.dromara.common.web.core.BaseController {
    private final GzJpSnapFxService service;
    public record Input(@NotNull @DecimalMin(value="0", inclusive=false) @Digits(integer=6, fraction=6) BigDecimal ratePer100jpy) { }
    @PostMapping @SaCheckPermission("gz:jp:snap:config:fx") @RepeatSubmit
    @Log(title="代切手动汇率", businessType=BusinessType.INSERT)
    public R<GzJpFxRate> create(@Valid @RequestBody Input input) { return R.ok(service.manual(input.ratePer100jpy())); }
    @GetMapping("/latest") @SaCheckPermission("gz:jp:snap:config:query")
    public R<GzJpSnapFxService.Latest> latest() { return R.ok(service.latest()); }
    @GetMapping @SaCheckPermission("gz:jp:snap:config:query")
    public R<List<GzJpFxRate>> history() { return R.ok(service.history()); }
}
