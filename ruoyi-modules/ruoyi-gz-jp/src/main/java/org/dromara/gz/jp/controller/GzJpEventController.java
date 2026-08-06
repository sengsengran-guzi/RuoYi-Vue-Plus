package org.dromara.gz.jp.controller;

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
import org.dromara.gz.jp.domain.bo.GzJpEventBo;
import org.dromara.gz.jp.domain.bo.GzJpEventQueryBo;
import org.dromara.gz.jp.domain.vo.GzJpEventAdminVO;
import org.dromara.gz.jp.service.IGzJpEventService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * GZ-JP-101 拼团场管理（admin 端，UI:admin.event / FLOW:F-JP-01）。
 *
 * <p>路径前缀 {@code /system/gz/jp/event} —— 与 mp 端 {@code /app/gz/jp/event}
 * （{@link org.dromara.gz.jp.controller.applet.GzJpEventMpController}）区分。</p>
 *
 * <p>权限（menu_id 14001 页面 + 14002~14005 按钮）：</p>
 * <ul>
 *   <li>{@code gz:jp:event:list} — 列表 / 详情</li>
 *   <li>{@code gz:jp:event:add} — 新建</li>
 *   <li>{@code gz:jp:event:edit} — 编辑 + 开场 + 关场（状态流转复用 edit，同 gz-recycle toggle 先例）</li>
 *   <li>{@code gz:jp:event:remove} — 删除</li>
 * </ul>
 *
 * <p>状态流转严格走 {@code open} / {@code close} 端点，不允许通过 PUT 直接改 status。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-JP-101)
 */
@Slf4j
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/system/gz/jp/event")
public class GzJpEventController extends BaseController {

    private final IGzJpEventService eventService;

    /**
     * 分页查询场列表（全状态；status 按生效状态筛选）。
     */
    @SaCheckPermission("gz:jp:event:list")
    @GetMapping("/list")
    public TableDataInfo<GzJpEventAdminVO> list(GzJpEventQueryBo query, PageQuery pageQuery) {
        return eventService.selectAdminPage(query, pageQuery);
    }

    /**
     * 场详情（编辑页回填）。
     */
    @SaCheckPermission("gz:jp:event:list")
    @GetMapping("/{id}")
    public R<GzJpEventAdminVO> getInfo(@NotNull @PathVariable Long id) {
        GzJpEventAdminVO vo = eventService.selectAdminById(id);
        if (vo == null) {
            return R.fail("场不存在：" + id);
        }
        return R.ok(vo);
    }

    /**
     * 新建场（FLOW:F-JP-01.step1，status=draft，客人不可见）。
     */
    @SaCheckPermission("gz:jp:event:add")
    @Log(title = "拼团场", businessType = BusinessType.INSERT)
    @RepeatSubmit()
    @PostMapping
    public R<Long> add(@Validated(AddGroup.class) @RequestBody GzJpEventBo bo) {
        return R.ok("新建成功", eventService.insertByBo(bo));
    }

    /**
     * 编辑场（eventNo / status 不可改 —— service 内部忽略）。
     */
    @SaCheckPermission("gz:jp:event:edit")
    @Log(title = "拼团场", businessType = BusinessType.UPDATE)
    @RepeatSubmit()
    @PutMapping
    public R<Void> edit(@Validated(EditGroup.class) @RequestBody GzJpEventBo bo) {
        return toAjax(eventService.updateByBo(bo));
    }

    /**
     * 开场（FLOW:F-JP-01.step3）—— status → open，客人可见可下单。
     */
    @SaCheckPermission("gz:jp:event:edit")
    @Log(title = "拼团场开场", businessType = BusinessType.UPDATE)
    @RepeatSubmit()
    @PostMapping("/open/{id}")
    public R<Void> open(@NotNull @PathVariable Long id) {
        return toAjax(eventService.open(id));
    }

    /**
     * 关场（FLOW:F-JP-01.step4 店员手动分支）—— status → closed，不可再下单。
     */
    @SaCheckPermission("gz:jp:event:edit")
    @Log(title = "拼团场关场", businessType = BusinessType.UPDATE)
    @RepeatSubmit()
    @PostMapping("/close/{id}")
    public R<Void> close(@NotNull @PathVariable Long id) {
        return toAjax(eventService.close(id));
    }

    /**
     * 逻辑删（软删；进行中的场需先关场）。
     */
    @SaCheckPermission("gz:jp:event:remove")
    @Log(title = "拼团场", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public R<Void> remove(@NotEmpty @PathVariable Long[] ids) {
        return toAjax(eventService.deleteByIds(List.of(ids)));
    }
}
