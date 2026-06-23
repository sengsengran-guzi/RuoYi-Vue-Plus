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
import org.dromara.gz.recycle.domain.bo.GzRecycleIpBo;
import org.dromara.gz.recycle.domain.bo.GzRecycleIpQueryBo;
import org.dromara.gz.recycle.domain.vo.GzRecycleIpVO;
import org.dromara.gz.recycle.service.IGzRecycleIpService;
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
 * GZ-RECYCLE-004 回收 IP 主数据管理（admin 端，ADR-0012 §4 / 契约 15a §C.4）。
 *
 * <p>路径前缀 {@code /system/gz/recycle/ip}（与 price-rule {@code /system/gz/recycle/price-rule} 同域风格）。</p>
 *
 * <p>权限（DDL menu_id 13004 + 按钮 13030~13033，仅 owner role_id=100 授权）：</p>
 * <ul>
 *   <li>{@code gz:recycle:ip:list} — 列表 / 详情</li>
 *   <li>{@code gz:recycle:ip:add} — 新建</li>
 *   <li>{@code gz:recycle:ip:edit} — 编辑 / 启停</li>
 *   <li>{@code gz:recycle:ip:remove} — 软删</li>
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
@RequestMapping("/system/gz/recycle/ip")
public class GzRecycleIpController extends BaseController {

    private final IGzRecycleIpService ipService;

    /** 分页查询 IP 列表（全状态 + 按名称模糊 / 启用筛选）。 */
    @SaCheckPermission("gz:recycle:ip:list")
    @GetMapping("/list")
    public TableDataInfo<GzRecycleIpVO> list(GzRecycleIpQueryBo query, PageQuery pageQuery) {
        return ipService.selectPage(query, pageQuery);
    }

    /** IP 详情（编辑页回填）。 */
    @SaCheckPermission("gz:recycle:ip:list")
    @GetMapping("/{id}")
    public R<GzRecycleIpVO> getInfo(@NotNull @PathVariable Long id) {
        GzRecycleIpVO vo = ipService.selectById(id);
        if (vo == null) {
            return R.fail("回收 IP 不存在：" + id);
        }
        return R.ok(vo);
    }

    /** 新建 IP（默认 enabled=1，ipName 唯一校验）。 */
    @SaCheckPermission("gz:recycle:ip:add")
    @Log(title = "回收 IP", businessType = BusinessType.INSERT)
    @RepeatSubmit()
    @PostMapping
    public R<Long> add(@Validated(AddGroup.class) @RequestBody GzRecycleIpBo bo) {
        return R.ok("新建成功", ipService.insertByBo(bo));
    }

    /** 编辑 IP（ipName 唯一校验排除自身）。 */
    @SaCheckPermission("gz:recycle:ip:edit")
    @Log(title = "回收 IP", businessType = BusinessType.UPDATE)
    @RepeatSubmit()
    @PutMapping
    public R<Void> edit(@Validated(EditGroup.class) @RequestBody GzRecycleIpBo bo) {
        return toAjax(ipService.updateByBo(bo));
    }

    /** 逻辑删（软删 del_flag='1'；本项目 logicDeleteValue=1）。 */
    @SaCheckPermission("gz:recycle:ip:remove")
    @Log(title = "回收 IP", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public R<Void> remove(@NotEmpty @PathVariable Long[] ids) {
        return toAjax(ipService.deleteByIds(List.of(ids)));
    }

    /** 启用 / 停用切换（enabled 0/1）。 */
    @SaCheckPermission("gz:recycle:ip:edit")
    @Log(title = "回收 IP 启停", businessType = BusinessType.UPDATE)
    @RepeatSubmit()
    @PostMapping("/toggle/{id}")
    public R<Void> toggle(@NotNull @PathVariable Long id, @NotNull @RequestParam Integer enabled) {
        return toAjax(ipService.toggleEnabled(id, enabled));
    }
}
