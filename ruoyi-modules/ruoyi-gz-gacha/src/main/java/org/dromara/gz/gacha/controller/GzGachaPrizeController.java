package org.dromara.gz.gacha.controller;

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
import org.dromara.gz.gacha.domain.bo.GzGachaPrizeBo;
import org.dromara.gz.gacha.domain.bo.GzGachaPrizeQueryBo;
import org.dromara.gz.gacha.domain.vo.GzGachaPrizeVo;
import org.dromara.gz.gacha.service.IGzGachaPrizeService;
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
 * GZ-GACHA-101 奖品池 admin CRUD（admin 端，奖品池嵌在机器详情下）。
 *
 * <p>路径前缀 {@code /system/gz/gacha/prize}。{@code list} 支持按 {@code machineId} 过滤（查某机器奖品池，
 * AC 4）。权限（DDL menu_id 10000 段，仅租户 1001 owner）：
 * {@code gz:gacha:prize:list/query/add/edit/remove}。写操作 {@code @Log} AOP 落操作日志。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-GACHA-101)
 */
@Slf4j
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/system/gz/gacha/prize")
public class GzGachaPrizeController extends BaseController {

    private final IGzGachaPrizeService prizeService;

    /**
     * 分页查询奖品列表（machineId 过滤 + rarity / enabled / name 筛选）。
     */
    @SaCheckPermission("gz:gacha:prize:list")
    @GetMapping("/list")
    public TableDataInfo<GzGachaPrizeVo> list(GzGachaPrizeQueryBo query, PageQuery pageQuery) {
        return prizeService.selectAdminPage(query, pageQuery);
    }

    /**
     * 奖品详情（编辑页回填用）。
     */
    @SaCheckPermission("gz:gacha:prize:query")
    @GetMapping("/{id}")
    public R<GzGachaPrizeVo> getInfo(@NotNull @PathVariable Long id) {
        GzGachaPrizeVo vo = prizeService.selectAdminById(id);
        if (vo == null) {
            return R.fail("奖品不存在：" + id);
        }
        return R.ok(vo);
    }

    /**
     * 新建奖品（prize_no 系统生成；校验归属机器 + 稀有度 + 库存/权重；stockRemain 空默认=stockInitial）。
     */
    @SaCheckPermission("gz:gacha:prize:add")
    @Log(title = "扭蛋奖品", businessType = BusinessType.INSERT)
    @RepeatSubmit()
    @PostMapping
    public R<Long> add(@Validated(AddGroup.class) @RequestBody GzGachaPrizeBo bo) {
        return R.ok("新建成功", prizeService.insertByBo(bo));
    }

    /**
     * 编辑奖品（prize_no / machineId 不可改）。
     */
    @SaCheckPermission("gz:gacha:prize:edit")
    @Log(title = "扭蛋奖品", businessType = BusinessType.UPDATE)
    @RepeatSubmit()
    @PutMapping
    public R<Void> edit(@Validated(EditGroup.class) @RequestBody GzGachaPrizeBo bo) {
        return toAjax(prizeService.updateByBo(bo));
    }

    /**
     * 软删（del_flag=2）。
     */
    @SaCheckPermission("gz:gacha:prize:remove")
    @Log(title = "扭蛋奖品", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public R<Void> remove(@NotEmpty @PathVariable Long[] ids) {
        return toAjax(prizeService.deleteByIds(List.of(ids)));
    }
}
