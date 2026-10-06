package org.dromara.gz.jp.controller;
import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.gz.jp.service.internal.GzJpSnapReadService;
import org.dromara.gz.jp.service.internal.GzJpSnapNormalizer;
import org.springframework.web.bind.annotation.*;
@RestController @RequiredArgsConstructor @RequestMapping("/system/gz/jp/snap/term")
public class GzJpSnapTermController {
    private final GzJpSnapReadService service;
    public record NormalizeInput(@NotBlank String q) { }
    @PostMapping("/normalize") @SaCheckPermission("gz:jp:snap:term:list")
    public R<GzJpSnapNormalizer.Result> normalize(@Valid @RequestBody NormalizeInput input) { return R.ok(service.normalize(input.q())); }
}
