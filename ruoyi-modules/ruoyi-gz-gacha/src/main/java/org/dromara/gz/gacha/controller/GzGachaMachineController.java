package org.dromara.gz.gacha.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.constraints.NotBlank;
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
import org.dromara.gz.gacha.domain.bo.GzGachaMachineBo;
import org.dromara.gz.gacha.domain.bo.GzGachaMachineQueryBo;
import org.dromara.gz.gacha.domain.vo.GzGachaMachineVo;
import org.dromara.gz.gacha.service.IGzGachaMachineService;
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
 * GZ-GACHA-101 扭蛋机 admin CRUD（admin 端）。
 *
 * <p>路径前缀 {@code /system/gz/gacha/machine} — 与 ruoyi 自带 {@code /system/...} 域名隔离；mp 端机器
 * 列表/详情由 GACHA-102/103 走 {@code /app/gz/gacha/...} 单独 controller。</p>
 *
 * <p>权限（DDL menu_id 10000 段，仅租户 1001 owner）：{@code gz:gacha:machine:list/query/add/edit/remove}。
 * 状态流转（上下架）走 changeStatus 端点（auto_off 仅 GACHA-104/cron，决策 D5）；写操作 {@code @Log} AOP 落操作日志。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-GACHA-101)
 */
@Slf4j
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/system/gz/gacha/machine")
public class GzGachaMachineController extends BaseController {

    private final IGzGachaMachineService machineService;

    /**
     * 分页查询扭蛋机列表（status / ipTag / name 筛选；含奖品池奖品数）。
     */
    @SaCheckPermission("gz:gacha:machine:list")
    @GetMapping("/list")
    public TableDataInfo<GzGachaMachineVo> list(GzGachaMachineQueryBo query, PageQuery pageQuery) {
        return machineService.selectAdminPage(query, pageQuery);
    }

    /**
     * 扭蛋机详情（编辑页回填用）。
     */
    @SaCheckPermission("gz:gacha:machine:query")
    @GetMapping("/{id}")
    public R<GzGachaMachineVo> getInfo(@NotNull @PathVariable Long id) {
        GzGachaMachineVo vo = machineService.selectAdminById(id);
        if (vo == null) {
            return R.fail("扭蛋机不存在：" + id);
        }
        return R.ok(vo);
    }

    /**
     * 新建扭蛋机（machine_no 系统生成；status 固定 off_shelf）。
     */
    @SaCheckPermission("gz:gacha:machine:add")
    @Log(title = "扭蛋机", businessType = BusinessType.INSERT)
    @RepeatSubmit()
    @PostMapping
    public R<Long> add(@Validated(AddGroup.class) @RequestBody GzGachaMachineBo bo) {
        return R.ok("新建成功", machineService.insertByBo(bo));
    }

    /**
     * 编辑扭蛋机（machine_no / status / salesCount 不可改 — status 走 changeStatus）。
     */
    @SaCheckPermission("gz:gacha:machine:edit")
    @Log(title = "扭蛋机", businessType = BusinessType.UPDATE)
    @RepeatSubmit()
    @PutMapping
    public R<Void> edit(@Validated(EditGroup.class) @RequestBody GzGachaMachineBo bo) {
        return toAjax(machineService.updateByBo(bo));
    }

    /**
     * 手动上下架（on_shelf ↔ off_shelf；targetStatus=auto_off 拒绝，决策 D5）。
     *
     * @param id           机器主键
     * @param targetStatus 目标态（on_shelf / off_shelf）
     */
    @SaCheckPermission("gz:gacha:machine:edit")
    @Log(title = "扭蛋机上下架", businessType = BusinessType.UPDATE)
    @RepeatSubmit()
    @PutMapping("/changeStatus")
    public R<Void> changeStatus(@NotNull @RequestParam("id") Long id,
                                @NotBlank @RequestParam("targetStatus") String targetStatus) {
        return toAjax(machineService.changeStatus(id, targetStatus));
    }

    /**
     * 软删（del_flag=2；仍有奖品拒删，返回明确 msg）。
     */
    @SaCheckPermission("gz:gacha:machine:remove")
    @Log(title = "扭蛋机", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public R<Void> remove(@NotEmpty @PathVariable Long[] ids) {
        return toAjax(machineService.deleteByIds(List.of(ids)));
    }
}
