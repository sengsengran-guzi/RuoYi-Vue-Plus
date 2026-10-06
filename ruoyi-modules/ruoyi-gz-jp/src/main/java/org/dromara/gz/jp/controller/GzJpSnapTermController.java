package org.dromara.gz.jp.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.gz.jp.domain.bo.GzJpSnapTermBo;
import org.dromara.gz.jp.domain.entity.GzJpSnapTerm;
import org.dromara.gz.jp.service.internal.GzJpSnapNormalizer;
import org.dromara.gz.jp.service.internal.GzJpSnapTermService;
import org.springframework.web.bind.annotation.*;

@RestController @RequiredArgsConstructor @RequestMapping("/system/gz/jp/snap/term")
public class GzJpSnapTermController {
    private final GzJpSnapTermService service;
    public record NormalizeInput(@NotBlank @Size(max = 500) String q) { }
    public record EnabledInput(@NotNull Boolean enabled) { }
    @GetMapping("/list") @SaCheckPermission("gz:jp:snap:term:query")
    public TableDataInfo<GzJpSnapTerm> list(String keyword, String termType, Boolean enabled, PageQuery page) { return service.list(keyword, termType, enabled, page); }
    @PostMapping @SaCheckPermission("gz:jp:snap:term:add")
    public R<Long> add(@Valid @RequestBody GzJpSnapTermBo input) { return R.ok(service.add(input)); }
    @PutMapping("/{id}") @SaCheckPermission("gz:jp:snap:term:edit")
    public R<Void> edit(@PathVariable Long id, @Valid @RequestBody GzJpSnapTermBo input) { service.edit(id, input); return R.ok(); }
    @PutMapping("/{id}/enabled") @SaCheckPermission("gz:jp:snap:term:edit")
    public R<Void> enabled(@PathVariable Long id, @Valid @RequestBody EnabledInput input) { service.enabled(id, input.enabled()); return R.ok(); }
    @DeleteMapping("/{id}") @SaCheckPermission("gz:jp:snap:term:remove")
    public R<Void> remove(@PathVariable Long id) { service.remove(id); return R.ok(); }
    @PostMapping("/normalize") @SaCheckPermission("gz:jp:snap:term:query")
    public R<GzJpSnapNormalizer.Result> normalize(@Valid @RequestBody NormalizeInput input) { return R.ok(service.normalize(input.q())); }
}
