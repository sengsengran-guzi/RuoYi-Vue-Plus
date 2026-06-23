package org.dromara.gz.recycle.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.R;
import org.dromara.common.core.validate.AddGroup;
import org.dromara.common.core.validate.EditGroup;
import org.dromara.common.idempotent.annotation.RepeatSubmit;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.common.web.core.BaseController;
import org.dromara.gz.recycle.domain.bo.GzRecycleQtyRangeBo;
import org.dromara.gz.recycle.domain.bo.GzRecycleQtyRangeQueryBo;
import org.dromara.gz.recycle.domain.vo.GzRecycleQtyRangeVO;
import org.dromara.gz.recycle.service.IGzRecycleQtyRangeService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * GZ-RECYCLE-004 回收数量桶 + 预计回收时长管理（admin 端，ADR-0012 §3 / 契约 15a §C.5）。
 *
 * <p>路径前缀 {@code /system/gz/recycle/qtyRange}（与 price-rule / ip 同域风格）。</p>
 *
 * <p>权限（DDL menu_id 13005 + 按钮 13040~13043，仅 owner role_id=100 授权）：</p>
 * <ul>
 *   <li>{@code gz:recycle:qtyRange:list} — 列表 / 详情</li>
 *   <li>{@code gz:recycle:qtyRange:add} — 新建</li>
 *   <li>{@code gz:recycle:qtyRange:edit} — 编辑 / 启停</li>
 *   <li>{@code gz:recycle:qtyRange:remove} — 软删</li>
 * </ul>
 *
 * <p>写操作走 {@code @Log} AOP 写入操作日志。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-RECYCLE-004)
 */
@Slf4j
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/system/gz/recycle/qtyRange")
public class GzRecycleQtyRangeController extends BaseController {

    private final IGzRecycleQtyRangeService qtyRangeService;

    /** 分页查询桶列表（全状态 + 按 code 模糊 / 启用筛选）。 */
    @SaCheckPermission("gz:recycle:qtyRange:list")
    @GetMapping("/list")
    public TableDataInfo<GzRecycleQtyRangeVO> list(GzRecycleQtyRangeQueryBo query, PageQuery pageQuery) {
        return qtyRangeService.selectPage(query, pageQuery);
    }

    /** 桶详情（编辑页回填）。 */
    @SaCheckPermission("gz:recycle:qtyRange:list")
    @GetMapping("/{id}")
    public R<GzRecycleQtyRangeVO> getInfo(@NotNull @PathVariable Long id) {
        GzRecycleQtyRangeVO vo = qtyRangeService.selectById(id);
        if (vo == null) {
            return R.fail("数量桶不存在：" + id);
        }
        return R.ok(vo);
    }

    /** 新建桶（默认 enabled=1，code 唯一校验）。 */
    @SaCheckPermission("gz:recycle:qtyRange:add")
    @Log(title = "回收数量桶", businessType = BusinessType.INSERT)
    @RepeatSubmit()
    @PostMapping
    public R<Long> add(@Validated(AddGroup.class) @RequestBody GzRecycleQtyRangeBo bo) {
        return R.ok("新建成功", qtyRangeService.insertByBo(bo));
    }

    /** 编辑桶（code 唯一校验排除自身）。 */
    @SaCheckPermission("gz:recycle:qtyRange:edit")
    @Log(title = "回收数量桶", businessType = BusinessType.UPDATE)
    @RepeatSubmit()
    @PutMapping
    public R<Void> edit(@Validated(EditGroup.class) @RequestBody GzRecycleQtyRangeBo bo) {
        return toAjax(qtyRangeService.updateByBo(bo));
    }

    /** 逻辑删（软删 del_flag='1'；本项目 logicDeleteValue=1）。 */
    @SaCheckPermission("gz:recycle:qtyRange:remove")
    @Log(title = "回收数量桶", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public R<Void> remove(@NotEmpty @PathVariable Long[] ids) {
        return toAjax(qtyRangeService.deleteByIds(List.of(ids)));
    }

    /** 启用 / 停用切换（enabled 0/1）。 */
    @SaCheckPermission("gz:recycle:qtyRange:edit")
    @Log(title = "回收数量桶启停", businessType = BusinessType.UPDATE)
    @RepeatSubmit()
    @PostMapping("/toggle/{id}")
    public R<Void> toggle(@NotNull @PathVariable Long id, @NotNull @RequestParam Integer enabled) {
        return toAjax(qtyRangeService.toggleEnabled(id, enabled));
    }
}
