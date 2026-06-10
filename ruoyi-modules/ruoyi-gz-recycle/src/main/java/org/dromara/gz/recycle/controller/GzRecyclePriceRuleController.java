package org.dromara.gz.recycle.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.constraints.Min;
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
import org.dromara.gz.recycle.domain.bo.GzRecyclePriceRuleBo;
import org.dromara.gz.recycle.domain.bo.GzRecyclePriceRuleQueryBo;
import org.dromara.gz.recycle.domain.vo.GzRecycleEstimateVO;
import org.dromara.gz.recycle.domain.vo.GzRecyclePriceRuleVO;
import org.dromara.gz.recycle.service.IGzRecyclePriceRuleService;
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
 * GZ-RECYCLE-001 回收价目表管理（admin 端）+ 估价命中（D14 RECYCLE-002 mp 消费）。
 *
 * <p>路径前缀 {@code /system/gz/recycle/price-rule}；与 ruoyi 自带 {@code /system/...} 域名隔离。</p>
 *
 * <p>权限（DDL menu_id 13001 + 按钮 13010~13014，仅 owner role_id=100 授权）：</p>
 * <ul>
 *   <li>{@code gz:recycle:priceRule:list} — 列表 / 详情</li>
 *   <li>{@code gz:recycle:priceRule:add} — 新建</li>
 *   <li>{@code gz:recycle:priceRule:edit} — 编辑 / 启停</li>
 *   <li>{@code gz:recycle:priceRule:remove} — 软删</li>
 *   <li>{@code gz:recycle:priceRule:estimate} — 估价试算（admin 调试 + D14 mp 复用）</li>
 * </ul>
 *
 * <p>区间不重叠校验在 service 保存路径（doc/11 §12.1 末段）。写操作走 {@code @Log} AOP 写入操作日志。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-RECYCLE-001)
 */
@Slf4j
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/system/gz/recycle/price-rule")
public class GzRecyclePriceRuleController extends BaseController {

    private final IGzRecyclePriceRuleService priceRuleService;

    /** 分页查询价目表（全状态 + 按品类 / 启用筛选）。 */
    @SaCheckPermission("gz:recycle:priceRule:list")
    @GetMapping("/list")
    public TableDataInfo<GzRecyclePriceRuleVO> list(GzRecyclePriceRuleQueryBo query, PageQuery pageQuery) {
        return priceRuleService.selectPage(query, pageQuery);
    }

    /** 价目规则详情（编辑页回填）。 */
    @SaCheckPermission("gz:recycle:priceRule:list")
    @GetMapping("/{id}")
    public R<GzRecyclePriceRuleVO> getInfo(@NotNull @PathVariable Long id) {
        GzRecyclePriceRuleVO vo = priceRuleService.selectById(id);
        if (vo == null) {
            return R.fail("价目规则不存在：" + id);
        }
        return R.ok(vo);
    }

    /** 新建价目规则（默认 enabled=1，区间不重叠校验）。 */
    @SaCheckPermission("gz:recycle:priceRule:add")
    @Log(title = "回收价目表", businessType = BusinessType.INSERT)
    @RepeatSubmit()
    @PostMapping
    public R<Long> add(@Validated(AddGroup.class) @RequestBody GzRecyclePriceRuleBo bo) {
        return R.ok("新建成功", priceRuleService.insertByBo(bo));
    }

    /** 编辑价目规则（区间不重叠校验排除自身）。 */
    @SaCheckPermission("gz:recycle:priceRule:edit")
    @Log(title = "回收价目表", businessType = BusinessType.UPDATE)
    @RepeatSubmit()
    @PutMapping
    public R<Void> edit(@Validated(EditGroup.class) @RequestBody GzRecyclePriceRuleBo bo) {
        return toAjax(priceRuleService.updateByBo(bo));
    }

    /** 逻辑删（软删 del_flag='1'；本项目 logicDeleteValue=1）。 */
    @SaCheckPermission("gz:recycle:priceRule:remove")
    @Log(title = "回收价目表", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public R<Void> remove(@NotEmpty @PathVariable Long[] ids) {
        return toAjax(priceRuleService.deleteByIds(List.of(ids)));
    }

    /** 启用 / 停用切换（enabled 0/1）。 */
    @SaCheckPermission("gz:recycle:priceRule:edit")
    @Log(title = "回收价目表启停", businessType = BusinessType.UPDATE)
    @RepeatSubmit()
    @PostMapping("/toggle/{id}")
    public R<Void> toggle(@NotNull @PathVariable Long id, @NotNull @RequestParam Integer enabled) {
        return toAjax(priceRuleService.toggleEnabled(id, enabled));
    }

    /** 估价试算（admin 调试 + D14 RECYCLE-002 mp 填单复用）。 */
    @SaCheckPermission("gz:recycle:priceRule:estimate")
    @GetMapping("/estimate")
    public R<GzRecycleEstimateVO> estimate(@NotBlank @RequestParam String category,
                                           @NotNull @Min(1) @RequestParam Integer qty) {
        return R.ok(priceRuleService.estimate(category, qty));
    }
}
