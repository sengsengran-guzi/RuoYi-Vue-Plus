package org.dromara.gz.common.recycle.controller.admin;

import cn.dev33.satoken.annotation.SaCheckPermission;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.R;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.common.web.core.BaseController;
import org.dromara.gz.common.recycle.domain.bo.RecycleActionBo;
import org.dromara.gz.common.recycle.domain.vo.RecycleBinItemVO;
import org.dromara.gz.common.recycle.service.IGzRecycleBinService;
import org.dromara.gz.common.recycle.service.internal.RecycleEntityRegistry;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * GZ-ADMIN-108 软删恢复回收站（admin 端，owner 限定）。
 *
 * <p>路径前缀 {@code /system/gz/recycle-bin}（对齐既有 gz admin 控制器约定）。跨业务表治理
 * {@code del_flag='2'} 软删记录：列表 / 恢复 / 归档；物理清理由 SnailJob 调 service（不在本控制器）。</p>
 *
 * <p>权限（DDL menu 11006，财务/数据敏感仅 owner role_id=100 授权）：</p>
 * <ul>
 *   <li>{@code gz:recycle:bin:list} — 回收站查询</li>
 *   <li>{@code gz:recycle:bin:restore} — 恢复</li>
 *   <li>{@code gz:recycle:bin:archive} — 立即归档</li>
 * </ul>
 *
 * @author kevin-coder (sensenran-guzi · GZ-ADMIN-108)
 */
@Slf4j
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/system/gz/recycle-bin")
public class GzRecycleBinAdminController extends BaseController {

    private final IGzRecycleBinService recycleBinService;

    /**
     * 回收站列表（聚合各业务表 del_flag='2'，按删除时间倒序，内存分页）。
     */
    @SaCheckPermission("gz:recycle:bin:list")
    @GetMapping("/list")
    public TableDataInfo<RecycleBinItemVO> list(@RequestParam(value = "entityType", required = false) String entityType,
                                                @RequestParam(value = "entityName", required = false) String entityName,
                                                @RequestParam(value = "startTime", required = false) String startTime,
                                                @RequestParam(value = "endTime", required = false) String endTime,
                                                @RequestParam(value = "pageNum", defaultValue = "1") int pageNum,
                                                @RequestParam(value = "pageSize", defaultValue = "10") int pageSize) {
        return recycleBinService.list(entityType, entityName, startTime, endTime, pageNum, pageSize);
    }

    /**
     * 注册的实体类型清单（前端筛选下拉，entityType + 中文标签）。
     */
    @SaCheckPermission("gz:recycle:bin:list")
    @GetMapping("/entity-types")
    public R<List<RecycleEntityRegistry.Def>> entityTypes() {
        return R.ok(RecycleEntityRegistry.all());
    }

    /**
     * 恢复：del_flag '2' → '0'。影响 0 行 → 业务错误（不静默成功）。
     */
    @Log(title = "回收站恢复", businessType = BusinessType.UPDATE)
    @SaCheckPermission("gz:recycle:bin:restore")
    @PostMapping("/restore")
    public R<Void> restore(@Validated @RequestBody RecycleActionBo bo) {
        String name = recycleBinService.restore(bo.getEntityType(), bo.getEntityId());
        return R.ok("已恢复：" + name, null);
    }

    /**
     * 立即归档：archived_flag=1（del_flag 保持 '2'，标记可被 SnailJob 物理清理）。
     */
    @Log(title = "回收站归档", businessType = BusinessType.UPDATE)
    @SaCheckPermission("gz:recycle:bin:archive")
    @PostMapping("/archive")
    public R<Void> archive(@Validated @RequestBody RecycleActionBo bo) {
        String name = recycleBinService.archive(bo.getEntityType(), bo.getEntityId());
        return R.ok("已归档：" + name, null);
    }
}
