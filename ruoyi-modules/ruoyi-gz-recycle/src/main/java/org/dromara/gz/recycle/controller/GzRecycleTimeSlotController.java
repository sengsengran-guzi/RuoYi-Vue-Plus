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
import org.dromara.gz.recycle.domain.bo.GzRecycleTimeSlotBo;
import org.dromara.gz.recycle.domain.bo.GzRecycleTimeSlotQueryBo;
import org.dromara.gz.recycle.domain.vo.GzRecycleTimeSlotVO;
import org.dromara.gz.recycle.service.IGzRecycleTimeSlotService;
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
 * GZ-RECYCLE-006 回收到店时段管理（admin 端，按门店可配，取代写死的上午/下午两档）。
 *
 * <p>路径前缀 {@code /system/gz/recycle/timeSlot}（与 qtyRange / ip 同域风格）。</p>
 *
 * <p>权限（DDL menu_id 13006 + 按钮 13050~13053，仅 owner role_id=100 授权）：</p>
 * <ul>
 *   <li>{@code gz:recycle:timeSlot:list} — 列表 / 详情</li>
 *   <li>{@code gz:recycle:timeSlot:add} — 新建</li>
 *   <li>{@code gz:recycle:timeSlot:edit} — 编辑 / 启停</li>
 *   <li>{@code gz:recycle:timeSlot:remove} — 软删</li>
 * </ul>
 *
 * <p>写操作走 {@code @Log} AOP 写入操作日志。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-RECYCLE-006)
 */
@Slf4j
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/system/gz/recycle/timeSlot")
public class GzRecycleTimeSlotController extends BaseController {

    private final IGzRecycleTimeSlotService timeSlotService;

    /** 分页查询时段列表（按门店 / 启用筛选，全状态）。 */
    @SaCheckPermission("gz:recycle:timeSlot:list")
    @GetMapping("/list")
    public TableDataInfo<GzRecycleTimeSlotVO> list(GzRecycleTimeSlotQueryBo query, PageQuery pageQuery) {
        return timeSlotService.selectPage(query, pageQuery);
    }

    /** 时段详情（编辑页回填）。 */
    @SaCheckPermission("gz:recycle:timeSlot:list")
    @GetMapping("/{id}")
    public R<GzRecycleTimeSlotVO> getInfo(@NotNull @PathVariable Long id) {
        GzRecycleTimeSlotVO vo = timeSlotService.selectById(id);
        if (vo == null) {
            return R.fail("时段不存在：" + id);
        }
        return R.ok(vo);
    }

    /** 新建时段（默认 enabled=1，end&gt;start + 同门店唯一校验）。 */
    @SaCheckPermission("gz:recycle:timeSlot:add")
    @Log(title = "回收到店时段", businessType = BusinessType.INSERT)
    @RepeatSubmit()
    @PostMapping
    public R<Long> add(@Validated(AddGroup.class) @RequestBody GzRecycleTimeSlotBo bo) {
        return R.ok("新建成功", timeSlotService.insertByBo(bo));
    }

    /** 编辑时段（end&gt;start + 同门店唯一校验排除自身）。 */
    @SaCheckPermission("gz:recycle:timeSlot:edit")
    @Log(title = "回收到店时段", businessType = BusinessType.UPDATE)
    @RepeatSubmit()
    @PutMapping
    public R<Void> edit(@Validated(EditGroup.class) @RequestBody GzRecycleTimeSlotBo bo) {
        return toAjax(timeSlotService.updateByBo(bo));
    }

    /** 逻辑删（软删 del_flag='1'；本项目 logicDeleteValue=1）。 */
    @SaCheckPermission("gz:recycle:timeSlot:remove")
    @Log(title = "回收到店时段", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public R<Void> remove(@NotEmpty @PathVariable Long[] ids) {
        return toAjax(timeSlotService.deleteByIds(List.of(ids)));
    }

    /** 启用 / 停用切换（enabled 0/1）。 */
    @SaCheckPermission("gz:recycle:timeSlot:edit")
    @Log(title = "回收到店时段启停", businessType = BusinessType.UPDATE)
    @RepeatSubmit()
    @PostMapping("/toggle/{id}")
    public R<Void> toggle(@NotNull @PathVariable Long id, @NotNull @RequestParam Integer enabled) {
        return toAjax(timeSlotService.toggleEnabled(id, enabled));
    }
}
