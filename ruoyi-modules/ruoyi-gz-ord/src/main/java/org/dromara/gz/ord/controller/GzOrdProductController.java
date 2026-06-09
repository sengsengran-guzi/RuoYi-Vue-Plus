package org.dromara.gz.ord.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.R;
import org.dromara.common.core.validate.AddGroup;
import org.dromara.common.core.validate.EditGroup;
import org.dromara.common.excel.core.ExcelResult;
import org.dromara.common.excel.utils.ExcelUtil;
import org.dromara.common.idempotent.annotation.RepeatSubmit;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.common.web.core.BaseController;
import org.dromara.gz.ord.domain.bo.GzOrdProductBo;
import org.dromara.gz.ord.domain.bo.GzOrdProductBatchStatusBo;
import org.dromara.gz.ord.domain.bo.GzOrdProductQueryBo;
import org.dromara.gz.ord.domain.excel.GzOrdProductExportVo;
import org.dromara.gz.ord.domain.excel.GzOrdProductImportVo;
import org.dromara.gz.ord.domain.vo.GzOrdBatchStatusVO;
import org.dromara.gz.ord.domain.vo.GzOrdProductAdminVO;
import org.dromara.gz.ord.domain.vo.GzOrdProductImportResultVO;
import org.dromara.gz.ord.service.IGzOrdProductService;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * GZ-ORD-101 预购商品 + SKU admin CRUD（admin 端）。
 *
 * <p>路径前缀 {@code /system/gz/ord/product} — 与 ruoyi 自带 {@code /system/...} 域名隔离；mp 端商品
 * 列表/详情由 ORD-102/103 走 {@code /app/gz/ord/...} 单独 controller。</p>
 *
 * <p>权限（DDL menu_id 9100 段，仅租户 1001 owner）：</p>
 * <ul>
 *   <li>{@code gz:ord:product:list} — 列表/详情（9100）</li>
 *   <li>{@code gz:ord:product:add} — 新建（9101）</li>
 *   <li>{@code gz:ord:product:edit} — 编辑（9102）</li>
 *   <li>{@code gz:ord:product:changeStatus} — 上下架（9103）</li>
 *   <li>{@code gz:ord:product:remove} — 删除（9104）</li>
 * </ul>
 *
 * <p>状态流转（上下架）走 changeStatus 端点（auto_off 仅 cron，决策 D5）；写操作 {@code @Log} AOP 落操作日志。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-ORD-101)
 */
@Slf4j
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/system/gz/ord/product")
public class GzOrdProductController extends BaseController {

    private final IGzOrdProductService productService;

    /**
     * 分页查询商品列表（status / ipTag / name 筛选；列表不投影 description_html / SKU）。
     */
    @SaCheckPermission("gz:ord:product:list")
    @GetMapping("/list")
    public TableDataInfo<GzOrdProductAdminVO> list(GzOrdProductQueryBo query, PageQuery pageQuery) {
        return productService.selectAdminPage(query, pageQuery);
    }

    /**
     * 商品详情（含 SKU 列表 + description_html，编辑页回填用）。
     */
    @SaCheckPermission("gz:ord:product:list")
    @GetMapping("/{id}")
    public R<GzOrdProductAdminVO> getInfo(@NotNull @PathVariable Long id) {
        GzOrdProductAdminVO vo = productService.selectAdminById(id);
        if (vo == null) {
            return R.fail("商品不存在：" + id);
        }
        return R.ok(vo);
    }

    /**
     * 新建商品 + 子 SKU（同事务；product_no / sku_no 系统生成；status 固定 off_shelf）。
     */
    @SaCheckPermission("gz:ord:product:add")
    @Log(title = "预购商品", businessType = BusinessType.INSERT)
    @RepeatSubmit()
    @PostMapping
    public R<Long> add(@Validated(AddGroup.class) @RequestBody GzOrdProductBo bo) {
        return R.ok("新建成功", productService.insertByBo(bo));
    }

    /**
     * 编辑商品 + SKU diff（同事务；status / product_no 不可改 — 走 changeStatus）。
     */
    @SaCheckPermission("gz:ord:product:edit")
    @Log(title = "预购商品", businessType = BusinessType.UPDATE)
    @RepeatSubmit()
    @PutMapping
    public R<Void> edit(@Validated(EditGroup.class) @RequestBody GzOrdProductBo bo) {
        return toAjax(productService.updateByBo(bo));
    }

    /**
     * 手动上下架（on_shelf ↔ off_shelf；targetStatus=auto_off 拒绝，决策 D5）。
     *
     * @param id           商品主键
     * @param targetStatus 目标态（on_shelf / off_shelf）
     */
    @SaCheckPermission("gz:ord:product:changeStatus")
    @Log(title = "预购商品上下架", businessType = BusinessType.UPDATE)
    @RepeatSubmit()
    @PutMapping("/changeStatus")
    public R<Void> changeStatus(@NotNull @RequestParam("id") Long id,
                                @NotBlank @RequestParam("targetStatus") String targetStatus) {
        return toAjax(productService.changeStatus(id, targetStatus));
    }

    /**
     * 软删（del_flag=2；被订单引用拒删，返回明确 msg）。
     */
    @SaCheckPermission("gz:ord:product:remove")
    @Log(title = "预购商品", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public R<Void> remove(@NotEmpty @PathVariable Long[] ids) {
        return toAjax(productService.deleteByIds(List.of(ids)));
    }

    /**
     * 批量上下架（GZ-ADMIN-101 AC 7）。一次 {@code UPDATE ... WHERE id IN(...)}；不满足条件的项
     * （auto_off / 无 SKU / 已截止）过滤进 skipped 不报错（R5）。targetStatus 仅 on_shelf / off_shelf。
     */
    @SaCheckPermission("gz:ord:product:status")
    @Log(title = "预购商品批量上下架", businessType = BusinessType.UPDATE)
    @RepeatSubmit()
    @PutMapping("/status")
    public R<GzOrdBatchStatusVO> batchStatus(@Validated @RequestBody GzOrdProductBatchStatusBo bo) {
        return R.ok(productService.batchUpdateStatus(bo.getIds(), bo.getStatus()));
    }

    /**
     * 导出当前筛选结果（GZ-ADMIN-101 AC 8）。product × SKU 平铺；金额分 → 元；status 中文。
     */
    @SaCheckPermission("gz:ord:product:export")
    @Log(title = "预购商品", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(GzOrdProductQueryBo query, HttpServletResponse response) {
        List<GzOrdProductExportVo> rows = productService.exportList(query);
        ExcelUtil.exportExcel(rows, "预购商品", GzOrdProductExportVo.class, response);
    }

    /**
     * 下载导入模板（GZ-ADMIN-101 AC 8）。空数据 + 表头，运营按表头填写后回传 importData。
     */
    @SaCheckPermission("gz:ord:product:import")
    @PostMapping("/importTemplate")
    public void importTemplate(HttpServletResponse response) {
        ExcelUtil.exportExcel(List.of(), "预购商品导入模板", GzOrdProductImportVo.class, response);
    }

    /**
     * Excel 批量导入商品 + SKU（GZ-ADMIN-101 AC 8）。同名商品多行合并；行级校验全失败回滚不部分提交。
     */
    @SaCheckPermission("gz:ord:product:import")
    @Log(title = "预购商品", businessType = BusinessType.IMPORT)
    @PostMapping(value = "/importData", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public R<GzOrdProductImportResultVO> importData(@RequestPart("file") MultipartFile file) throws Exception {
        ExcelResult<GzOrdProductImportVo> excelResult =
            ExcelUtil.importExcel(file.getInputStream(), GzOrdProductImportVo.class, false);
        GzOrdProductImportResultVO result = productService.importData(excelResult.getList());
        if (!result.isSuccess()) {
            return R.fail("导入失败：" + String.join("；", result.getErrors()), result);
        }
        return R.ok("导入成功：商品 " + result.getProductCount() + " 个 / SKU " + result.getSkuCount() + " 个", result);
    }
}
