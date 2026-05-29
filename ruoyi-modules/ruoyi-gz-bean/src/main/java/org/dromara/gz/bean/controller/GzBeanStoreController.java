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
import org.dromara.gz.bean.domain.bo.GzBeanStoreBo;
import org.dromara.gz.bean.domain.bo.GzBeanStoreQueryBo;
import org.dromara.gz.bean.domain.vo.GzBeanStoreVO;
import org.dromara.gz.bean.service.IGzBeanStoreService;
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
 * GZ-BEAN-001 拼豆门店管理（admin 端）。
 *
 * <p>路径前缀 {@code /system/gz/bean/store} — 与 ruoyi 自带 {@code /system/...} 等显式区分。</p>
 *
 * <p>权限（DDL menu_id 6002~6006）：</p>
 * <ul>
 *   <li>{@code gz:bean:store:list} — 列表 / 全量（owner + staff）</li>
 *   <li>{@code gz:bean:store:query} — 详情（owner + staff）</li>
 *   <li>{@code gz:bean:store:add} — 新增（owner）</li>
 *   <li>{@code gz:bean:store:edit} — 编辑（owner）</li>
 *   <li>{@code gz:bean:store:remove} — 删除（owner）</li>
 * </ul>
 *
 * <p>所有写操作走 {@code @Log} AOP 写入操作日志（GZ-SYS-006 规约 + ticket AC 5）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-BEAN-001)
 */
@Slf4j
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/system/gz/bean/store")
public class GzBeanStoreController extends BaseController {

    private final IGzBeanStoreService storeService;

    /**
     * 分页查询门店列表。
     */
    @SaCheckPermission("gz:bean:store:list")
    @GetMapping("/list")
    public TableDataInfo<GzBeanStoreVO> list(GzBeanStoreQueryBo query, PageQuery pageQuery) {
        return storeService.selectPageList(query, pageQuery);
    }

    /**
     * 全量列表（admin 账号管理下拉用 — 不分页，type='pindou' 全集）。
     *
     * <p>专给 ADMIN-002 staff 账号绑定 store_id 下拉用（替换硬编码占位）。</p>
     */
    @SaCheckPermission("gz:bean:store:list")
    @GetMapping("/options")
    public R<List<GzBeanStoreVO>> options() {
        return R.ok(storeService.selectOptions());
    }

    /**
     * 门店详情。
     */
    @SaCheckPermission("gz:bean:store:query")
    @GetMapping("/{id}")
    public R<GzBeanStoreVO> getInfo(@NotNull @PathVariable Long id) {
        GzBeanStoreVO vo = storeService.selectVoById(id);
        if (vo == null) {
            return R.fail("门店不存在：" + id);
        }
        return R.ok(vo);
    }

    /**
     * 新增门店。
     */
    @SaCheckPermission("gz:bean:store:add")
    @Log(title = "拼豆门店", businessType = BusinessType.INSERT)
    @RepeatSubmit()
    @PostMapping
    public R<Void> add(@Validated(AddGroup.class) @RequestBody GzBeanStoreBo bo) {
        return toAjax(storeService.insertByBo(bo) ? 1 : 0);
    }

    /**
     * 编辑门店（storeNo 不可改 — service 内部忽略）。
     */
    @SaCheckPermission("gz:bean:store:edit")
    @Log(title = "拼豆门店", businessType = BusinessType.UPDATE)
    @RepeatSubmit()
    @PutMapping
    public R<Void> edit(@Validated(EditGroup.class) @RequestBody GzBeanStoreBo bo) {
        return toAjax(storeService.updateByBo(bo) ? 1 : 0);
    }

    /**
     * 删除门店（软删，按 id 集合）。
     *
     * <p>业务规则（ticket R2）：实际生产应禁止删除 active 门店；V1.0 仅软删，
     * 后续 BEAN-005 完工后增加「有 active 预约不可删」校验。</p>
     */
    @SaCheckPermission("gz:bean:store:remove")
    @Log(title = "拼豆门店", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public R<Void> remove(@NotEmpty @PathVariable Long[] ids) {
        return toAjax(storeService.deleteByIds(List.of(ids)) ? 1 : 0);
    }
}
