package org.dromara.gz.common.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.R;
import org.dromara.common.core.domain.model.LoginUser;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.excel.utils.ExcelUtil;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.gz.common.domain.bo.GzOperLogQueryBo;
import org.dromara.gz.common.domain.vo.GzOperLogVO;
import org.dromara.gz.common.service.IGzOperLogService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Set;

/**
 * GZ-SYS-006 操作日志（管理后台审计）。
 *
 * <p>路径前缀 <code>/system/gz/oper-log</code> — 与 ruoyi 自带 <code>/monitor/operlog</code> 显式区分；
 * 复用 sys_oper_log 表但提供 owner / staff 角色差异化 wrapper。</p>
 *
 * <p>权限差异：</p>
 * <ul>
 *   <li><b>owner</b>（role_key=owner）：看全部租户日志 / 详情 / 删除 / 导出（gz:oper-log:*）</li>
 *   <li><b>staff</b>（role_key=staff）：仅看 <i>自己</i> 的日志（强制 operName=当前用户名）<br>
 *       菜单只配 list + query 两个 perm，删除 / 导出按钮前端 v-hasPermi 自然隐藏；
 *       即便构造请求过来，list/query 也只查得到自己的</li>
 *   <li><b>superadmin</b>（ruoyi 自带）：作为开发兜底，按 owner 同样的全量逻辑处理</li>
 * </ul>
 *
 * <p>写日志由 ruoyi 自带 LogAspect / OperLogEvent / SysOperLogServiceImpl.recordOper 完成，
 * 本 Controller 仅做 SELECT + DELETE。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-SYS-006)
 */
@Slf4j
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/system/gz/oper-log")
public class GzOperLogController {

    /** owner / 超管 角色 key（superadmin 是 ruoyi 内置） */
    private static final Set<String> ALL_VIEW_ROLES = Set.of("owner", "superadmin");

    private final IGzOperLogService operLogService;

    /**
     * 分页查询操作日志。
     *
     * <p>staff 角色：强制覆盖 query.operName = 当前用户名（防止越权查别人）。</p>
     */
    @SaCheckPermission("gz:oper-log:list")
    @GetMapping("/list")
    public TableDataInfo<GzOperLogVO> list(GzOperLogQueryBo query, PageQuery pageQuery) {
        applyStaffScopeIfNeeded(query);
        return operLogService.selectPageList(query, pageQuery);
    }

    /**
     * 详情查询。
     *
     * <p>staff 角色访问详情时需校验日志归属（operName 必须等于自己）— 防越权。</p>
     */
    @SaCheckPermission("gz:oper-log:query")
    @GetMapping("/{operId}")
    public R<GzOperLogVO> getInfo(@PathVariable @NotNull Long operId) {
        GzOperLogVO vo = operLogService.selectById(operId);
        if (vo == null) {
            return R.fail("operLog.notFound: " + operId);
        }
        // staff 越权检查
        if (!isAllViewRole()) {
            String currentUser = LoginHelper.getUsername();
            if (!StringUtils.equals(currentUser, vo.getOperName())) {
                log.warn("[GZ-SYS-006] staff 越权访问拦截: user={} 尝试查 operId={} (operName={})",
                    currentUser, operId, vo.getOperName());
                return R.fail("operLog.forbidden");
            }
        }
        return R.ok(vo);
    }

    /**
     * 批量删除。仅 owner / superadmin（staff 无 gz:oper-log:remove perm 走不到这里）。
     */
    @Log(title = "谷子操作日志", businessType = BusinessType.DELETE)
    @SaCheckPermission("gz:oper-log:remove")
    @DeleteMapping("/{operIds}")
    public R<Void> remove(@PathVariable Long[] operIds) {
        operLogService.deleteByIds(operIds);
        return R.ok();
    }

    /**
     * 导出（仅 owner / superadmin）。
     */
    @Log(title = "谷子操作日志", businessType = BusinessType.EXPORT)
    @SaCheckPermission("gz:oper-log:export")
    @PostMapping("/export")
    public void export(GzOperLogQueryBo query, HttpServletResponse response) {
        applyStaffScopeIfNeeded(query);
        List<GzOperLogVO> list = operLogService.selectList(query);
        ExcelUtil.exportExcel(list, "谷子操作日志", GzOperLogVO.class, response);
    }

    /* ============ private ============ */

    /**
     * staff 角色自动注入 operName 过滤条件。
     */
    private void applyStaffScopeIfNeeded(GzOperLogQueryBo query) {
        if (!isAllViewRole()) {
            String currentUser = LoginHelper.getUsername();
            // staff 不允许传 operName（任何传入都被覆盖）— 防越权
            if (query.getOperName() != null && !StringUtils.equals(query.getOperName(), currentUser)) {
                log.warn("[GZ-SYS-006] staff scope override: user={} 传入 operName={} 被覆盖",
                    currentUser, query.getOperName());
            }
            query.setOperName(currentUser);
        }
    }

    /**
     * 是否为"看全部"角色（owner / superadmin）。
     */
    private boolean isAllViewRole() {
        LoginUser user = LoginHelper.getLoginUser();
        if (user == null) {
            return false;
        }
        Set<String> roles = user.getRolePermission();
        if (roles == null || roles.isEmpty()) {
            return false;
        }
        for (String role : roles) {
            if (ALL_VIEW_ROLES.contains(role)) {
                return true;
            }
        }
        return false;
    }
}
