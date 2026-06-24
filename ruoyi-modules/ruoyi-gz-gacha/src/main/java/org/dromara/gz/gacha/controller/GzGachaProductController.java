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
import org.dromara.gz.gacha.domain.bo.GzGachaProductBo;
import org.dromara.gz.gacha.domain.bo.GzGachaProductQueryBo;
import org.dromara.gz.gacha.domain.vo.GzGachaProductVo;
import org.dromara.gz.gacha.service.IGzGachaProductService;
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
 * ADR-0013 / GZ-GACHA-112 扭蛋产品库 admin CRUD（admin 端）。
 *
 * <p>路径前缀 {@code /system/gz/gacha/product}。权限（DDL menu_id 10005 + 按钮 10050-10054，仅租户 1001 owner）：
 * {@code gz:gacha:product:list/query/add/edit/remove}。{@code /options} 走 {@code list} 权（奖品池选产品下拉）。
 * 写操作 {@code @Log} AOP 落操作日志。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-GACHA-112)
 */
@Slf4j
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/system/gz/gacha/product")
public class GzGachaProductController extends BaseController {

    private final IGzGachaProductService productService;

    /**
     * 分页查询产品列表（name 模糊 / ipTag / enabled 筛选）。
     */
    @SaCheckPermission("gz:gacha:product:list")
    @GetMapping("/list")
    public TableDataInfo<GzGachaProductVo> list(GzGachaProductQueryBo query, PageQuery pageQuery) {
        return productService.selectAdminPage(query, pageQuery);
    }

    /**
     * 奖品池选产品下拉选项（仅 enabled=1，可选 ipTag 过滤）。走 list 权。
     */
    @SaCheckPermission("gz:gacha:product:list")
    @GetMapping("/options")
    public R<List<GzGachaProductVo>> options(@RequestParam(required = false) String ipTag) {
        return R.ok(productService.listOptions(ipTag));
    }

    /**
     * 产品详情（编辑页回填用）。
     */
    @SaCheckPermission("gz:gacha:product:query")
    @GetMapping("/{id}")
    public R<GzGachaProductVo> getInfo(@NotNull @PathVariable Long id) {
        GzGachaProductVo vo = productService.selectAdminById(id);
        if (vo == null) {
            return R.fail("产品不存在：" + id);
        }
        return R.ok(vo);
    }

    /**
     * 新建产品（product_no 系统生成；enabled 空默认 1）。
     */
    @SaCheckPermission("gz:gacha:product:add")
    @Log(title = "扭蛋产品库", businessType = BusinessType.INSERT)
    @RepeatSubmit()
    @PostMapping
    public R<Long> add(@Validated(AddGroup.class) @RequestBody GzGachaProductBo bo) {
        return R.ok("新建成功", productService.insertByBo(bo));
    }

    /**
     * 编辑产品（product_no 不可改）。
     */
    @SaCheckPermission("gz:gacha:product:edit")
    @Log(title = "扭蛋产品库", businessType = BusinessType.UPDATE)
    @RepeatSubmit()
    @PutMapping
    public R<Void> edit(@Validated(EditGroup.class) @RequestBody GzGachaProductBo bo) {
        return toAjax(productService.updateByBo(bo));
    }

    /**
     * 软删（del_flag=2；被投放线引用拒删，返回明确 msg）。
     */
    @SaCheckPermission("gz:gacha:product:remove")
    @Log(title = "扭蛋产品库", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public R<Void> remove(@NotEmpty @PathVariable Long[] ids) {
        return toAjax(productService.deleteByIds(List.of(ids)));
    }
}
