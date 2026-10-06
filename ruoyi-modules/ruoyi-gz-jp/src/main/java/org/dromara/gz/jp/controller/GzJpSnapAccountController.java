package org.dromara.gz.jp.controller;
import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.dromara.gz.jp.service.internal.GzJpSnapAccountAdminService;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController @RequiredArgsConstructor @RequestMapping("/system/gz/jp/snap/account")
public class GzJpSnapAccountController {
    private final GzJpSnapAccountAdminService service;
    public record Create(@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{1,32}") String accountCode,
        @NotBlank @Size(max=64) String nickname,@NotNull @Min(1) Integer dailyLimit,@NotNull @Min(0) Integer cooldownSec) {}
    public record Edit(@NotBlank @Size(max=64) String nickname,@NotNull @Min(1) Integer dailyLimit,@NotNull @Min(0) Integer cooldownSec) {}
    public record Enable(@NotNull Boolean enabled) {}
    public record Confirm(@AssertTrue boolean confirmed) {}
    public record Pause(@NotBlank @Size(max=255) String reason) {}
    @GetMapping("/list") @SaCheckPermission("gz:jp:snap:account:query")
    public R<List<GzJpSnapAccountAdminService.Row>> list(String accountCode){return R.ok(service.list(accountCode));}
    @PostMapping @SaCheckPermission("gz:jp:snap:account:edit") @Log(title="采购账号新增",businessType=BusinessType.INSERT)
    public R<Long> create(@Valid @RequestBody Create v){return R.ok(service.create(v.accountCode(),v.nickname(),v.dailyLimit(),v.cooldownSec()));}
    @PutMapping("/{id}") @SaCheckPermission("gz:jp:snap:account:edit") @Log(title="采购账号编辑",businessType=BusinessType.UPDATE)
    public R<Void> edit(@PathVariable Long id,@Valid @RequestBody Edit v){service.edit(id,v.nickname(),v.dailyLimit(),v.cooldownSec());return R.ok();}
    @PostMapping("/{id}/enabled") @SaCheckPermission("gz:jp:snap:account:edit") @Log(title="采购账号启停",businessType=BusinessType.UPDATE)
    public R<Void> enable(@PathVariable Long id,@Valid @RequestBody Enable v){service.enabled(id,v.enabled());return R.ok();}
    @PostMapping("/{id}/clear-pause") @SaCheckPermission("gz:jp:snap:account:edit") @Log(title="采购账号解除暂停",businessType=BusinessType.UPDATE)
    public R<Void> clear(@PathVariable Long id,@Valid @RequestBody Confirm v){service.clearPause(id);return R.ok();}
    @GetMapping("/auto-switch") @SaCheckPermission("gz:jp:snap:account:query")
    public R<GzJpSnapAccountAdminService.AutoSwitch> read(){return R.ok(service.autoSwitch());}
    @PostMapping("/auto-switch/resume") @SaCheckPermission("gz:jp:snap:account:switch") @Log(title="恢复自动下单",businessType=BusinessType.UPDATE)
    public R<Void> resume(@Valid @RequestBody Confirm v){service.resume(v.confirmed());return R.ok();}
    @PostMapping("/auto-switch/pause") @SaCheckPermission("gz:jp:snap:account:switch") @Log(title="暂停自动下单",businessType=BusinessType.UPDATE)
    public R<Void> pause(@Valid @RequestBody Pause v){service.pause(v.reason());return R.ok();}
}
