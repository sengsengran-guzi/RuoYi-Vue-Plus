package org.dromara.gz.bean.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.exception.NotPermissionException;
import cn.dev33.satoken.stp.StpUtil;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collection;
import java.util.List;
import java.util.function.Predicate;

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

    private static final String STORE_LIST_PERM = "gz:bean:store:list";

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
     * 全量列表（admin 下拉用 — 不分页）。
     *
     * <p>{@code ?scope=pindou|recycle} 收敛到某条业务线（GZ-BEAN-053：回收页门店下拉不该列出纯拼豆店）；
     * 不传 = 全部门店（ADMIN-002 账号绑定门店场景要看全集）。</p>
     *
     * <p><b>权限按 scope 放行，不再只认「门店管理」权限</b>（2026-09-23 prod 事故）：回收 / 拼豆各业务页打开时第一件事
     * 就是拉门店下拉，原先一律要求 {@code gz:bean:store:list}。这条权限是挂在「门店管理」菜单下的按钮节点，
     * 只要有人在角色管理里取消「门店管理 / 拼豆业务」那棵树，就会被连带取消 —— 回收店员 huishou 的看板因此
     * 报「当前操作没有权限」、门店下拉为空、看板整块空白，且报错完全看不出和门店有关。
     * 现在：持有该业务线任意权限（{@code gz:recycle:*} / {@code gz:bean:*}）即可读该业务线的门店下拉；
     * 不带 scope 的全集仍只给门店管理权限。只返回门店 id / 名称 / 地址等展示字段，门店的增删改权限不变。</p>
     */
    @GetMapping("/options")
    public R<List<GzBeanStoreVO>> options(@RequestParam(required = false) String scope) {
        if (!canReadStoreOptions(scope, StpUtil::hasPermission, StpUtil.getPermissionList())) {
            throw new NotPermissionException(STORE_LIST_PERM);
        }
        return R.ok(storeService.selectOptions(scope));
    }

    /**
     * 门店下拉的读权限判定（纯函数，便于单测）。
     *
     * @param scope    业务线 pindou / recycle；其它值（含 null）视为「全集」
     * @param hasPerm  判定是否持有某权限（生产传 {@code StpUtil::hasPermission}，已处理超管通配）
     * @param perms    当前用户的权限列表
     */
    static boolean canReadStoreOptions(String scope, Predicate<String> hasPerm, Collection<String> perms) {
        if (hasPerm.test(STORE_LIST_PERM)) {
            return true;
        }
        String prefix = "recycle".equals(scope) ? "gz:recycle:" : "pindou".equals(scope) ? "gz:bean:" : null;
        return prefix != null && perms != null && perms.stream().anyMatch(p -> p != null && p.startsWith(prefix));
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
