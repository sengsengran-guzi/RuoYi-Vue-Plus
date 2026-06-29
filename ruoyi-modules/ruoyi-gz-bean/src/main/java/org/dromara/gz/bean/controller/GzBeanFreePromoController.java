package org.dromara.gz.bean.controller;

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
import org.dromara.gz.bean.domain.bo.GzBeanFreePromoBo;
import org.dromara.gz.bean.domain.vo.GzBeanFreePromoVO;
import org.dromara.gz.bean.service.IGzBeanFreePromoService;
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
 * GZ-BEAN-025 拼豆前 N 名免费促销配置（admin 端，ADR-0015 §4）。
 *
 * <p>路径前缀 {@code /system/gz/bean/free-promo}。每门店一行配置：周期（day/week/days）+ 名额 N +
 * 促销起止 + 总开关。权限段 {@code gz:bean:promo:*}（GZ-BEAN menu 6000-6999 段）。</p>
 *
 * <p>所有写操作走 {@code @Log} AOP 写操作日志。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-BEAN-025)
 */
@Slf4j
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/system/gz/bean/free-promo")
public class GzBeanFreePromoController extends BaseController {

    private final IGzBeanFreePromoService freePromoService;

    /** 分页查询促销配置列表。 */
    @SaCheckPermission("gz:bean:promo:list")
    @GetMapping("/list")
    public TableDataInfo<GzBeanFreePromoVO> list(PageQuery pageQuery) {
        return freePromoService.selectPageList(pageQuery);
    }

    /** 促销配置详情。 */
    @SaCheckPermission("gz:bean:promo:query")
    @GetMapping("/{id}")
    public R<GzBeanFreePromoVO> getInfo(@NotNull @PathVariable Long id) {
        GzBeanFreePromoVO vo = freePromoService.selectVoById(id);
        if (vo == null) {
            return R.fail("促销配置不存在：" + id);
        }
        return R.ok(vo);
    }

    /** 按门店查促销配置（每门店一行；admin 表单回填用，无配置返回 null data）。 */
    @SaCheckPermission("gz:bean:promo:query")
    @GetMapping("/by-store/{storeId}")
    public R<GzBeanFreePromoVO> getByStore(@NotNull @PathVariable Long storeId) {
        return R.ok(freePromoService.selectByStoreId(storeId));
    }

    /** 新增促销配置（每门店一行，store_id 已存在则拒绝）。 */
    @SaCheckPermission("gz:bean:promo:add")
    @Log(title = "拼豆免费促销", businessType = BusinessType.INSERT)
    @RepeatSubmit()
    @PostMapping
    public R<Void> add(@Validated(AddGroup.class) @RequestBody GzBeanFreePromoBo bo) {
        return toAjax(freePromoService.insertByBo(bo) ? 1 : 0);
    }

    /** 编辑促销配置（storeId 不可改 — service 忽略）。 */
    @SaCheckPermission("gz:bean:promo:edit")
    @Log(title = "拼豆免费促销", businessType = BusinessType.UPDATE)
    @RepeatSubmit()
    @PutMapping
    public R<Void> edit(@Validated(EditGroup.class) @RequestBody GzBeanFreePromoBo bo) {
        return toAjax(freePromoService.updateByBo(bo) ? 1 : 0);
    }

    /** 删除促销配置（软删，按 id 集合）。 */
    @SaCheckPermission("gz:bean:promo:remove")
    @Log(title = "拼豆免费促销", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public R<Void> remove(@NotEmpty @PathVariable Long[] ids) {
        return toAjax(freePromoService.deleteByIds(List.of(ids)) ? 1 : 0);
    }
}
