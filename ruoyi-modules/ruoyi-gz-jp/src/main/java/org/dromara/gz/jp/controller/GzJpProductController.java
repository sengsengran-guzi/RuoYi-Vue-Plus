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
import org.dromara.gz.jp.domain.bo.GzJpProductBo;
import org.dromara.gz.jp.domain.bo.GzJpProductQueryBo;
import org.dromara.gz.jp.domain.bo.GzJpProductStatusBo;
import org.dromara.gz.jp.domain.vo.GzJpEventOptionVO;
import org.dromara.gz.jp.domain.vo.GzJpProductAdminVO;
import org.dromara.gz.jp.service.IGzJpEventService;
import org.dromara.gz.jp.service.IGzJpProductService;
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
 * GZ-JP-102 拼团商品管理（admin 端，UI:admin.product / FLOW:F-JP-01.step2）。
 *
 * <p>路径前缀 {@code /system/gz/jp/product} —— mp 只读接口 {@code /app/gz/jp/product}
 * 由 GZ-JP-103 另建。</p>
 *
 * <p>权限（menu_id 14010 页面 + 14011~14014 按钮）：</p>
 * <ul>
 *   <li>{@code gz:jp:product:list} — 列表 / 详情 / 场下拉</li>
 *   <li>{@code gz:jp:product:add} — 新建</li>
 *   <li>{@code gz:jp:product:edit} — 编辑 + 批量上下架（状态流转复用 edit，同场的 open/close 先例）</li>
 *   <li>{@code gz:jp:product:remove} — 删除</li>
 * </ul>
 *
 * <p>状态流转严格走 {@code /status} 端点，不允许通过 PUT 直接改 status。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-JP-102)
 */
@Slf4j
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/system/gz/jp/product")
public class GzJpProductController extends BaseController {

    private final IGzJpProductService productService;
    private final IGzJpEventService eventService;

    /**
     * 分页查询商品列表（顶部按场筛选）。
     */
    @SaCheckPermission("gz:jp:product:list")
    @GetMapping("/list")
    public TableDataInfo<GzJpProductAdminVO> list(GzJpProductQueryBo query, PageQuery pageQuery) {
        return productService.selectAdminPage(query, pageQuery);
    }

    /**
     * 场下拉选项（「按场筛选」+ 表单「所属场」共用）。
     *
     * <p>挂在商品权限下而非场权限下 —— 商品管理页只应依赖 {@code gz:jp:product:*}，
     * 不因为一个下拉就把整套场权限拖成前置条件。</p>
     */
    @SaCheckPermission("gz:jp:product:list")
    @GetMapping("/event-options")
    public R<List<GzJpEventOptionVO>> eventOptions() {
        return R.ok(eventService.selectOptions());
    }

    /**
     * 商品详情（编辑页回填）。
     */
    @SaCheckPermission("gz:jp:product:list")
    @GetMapping("/{id}")
    public R<GzJpProductAdminVO> getInfo(@NotNull @PathVariable Long id) {
        GzJpProductAdminVO vo = productService.selectAdminById(id);
        if (vo == null) {
            return R.fail("商品不存在：" + id);
        }
        return R.ok(vo);
    }

    /**
     * 新建商品（FLOW:F-JP-01.step2，status=off_shelf，需显式上架）。
     */
    @SaCheckPermission("gz:jp:product:add")
    @Log(title = "拼团商品", businessType = BusinessType.INSERT)
    @RepeatSubmit()
    @PostMapping
    public R<Long> add(@Validated(AddGroup.class) @RequestBody GzJpProductBo bo) {
        return R.ok("新建成功", productService.insertByBo(bo));
    }

    /**
     * 编辑商品（productNo / status 不可改 —— service 内部忽略）。
     */
    @SaCheckPermission("gz:jp:product:edit")
    @Log(title = "拼团商品", businessType = BusinessType.UPDATE)
    @RepeatSubmit()
    @PutMapping
    public R<Void> edit(@Validated(EditGroup.class) @RequestBody GzJpProductBo bo) {
        return toAjax(productService.updateByBo(bo));
    }

    /**
     * 批量上下架（UI:admin.product「支持多选批量上下架」）。
     *
     * <pre>
     * POST /system/gz/jp/product/status
     * { "ids": ["1","2"], "status": "on_shelf" }
     * </pre>
     *
     * @return 实际改动条数（已是目标态的项会被跳过，不计入）
     */
    @SaCheckPermission("gz:jp:product:edit")
    @Log(title = "拼团商品上下架", businessType = BusinessType.UPDATE)
    @RepeatSubmit()
    @PostMapping("/status")
    public R<Integer> changeStatus(@Validated @RequestBody GzJpProductStatusBo bo) {
        return R.ok("操作成功", productService.changeStatus(bo));
    }

    /**
     * 逻辑删（软删；已上架的商品需先下架）。
     */
    @SaCheckPermission("gz:jp:product:remove")
    @Log(title = "拼团商品", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public R<Void> remove(@NotEmpty @PathVariable Long[] ids) {
        return toAjax(productService.deleteByIds(List.of(ids)));
    }
}
