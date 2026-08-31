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
import org.dromara.gz.bean.domain.bo.GzBeanSeatBatchGenerateBo;
import org.dromara.gz.bean.domain.bo.GzBeanSeatBo;
import org.dromara.gz.bean.domain.bo.GzBeanSeatQueryBo;
import org.dromara.gz.bean.domain.vo.GzBeanSeatBatchGenerateResultVO;
import org.dromara.gz.bean.domain.vo.GzBeanSeatVO;
import org.dromara.gz.bean.service.IGzBeanSeatService;
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
 * GZ-BEAN-023 拼豆座位单元管理（admin 端，ADR-0015）。
 *
 * <p>路径前缀 {@code /system/gz/bean/seat}。座位单元挂桌型 config 之下，影院选座以具体座位为准；
 * admin 单独 CRUD / 启停 + 按桌型批量生成（不逐个手画）。</p>
 *
 * <p>权限（DDL menu_id 6021-6025）：</p>
 * <ul>
 *   <li>{@code gz:bean:seat:list} — 列表（owner + staff）</li>
 *   <li>{@code gz:bean:seat:add} / edit / remove / batchGenerate — 写（仅 owner）</li>
 * </ul>
 *
 * @author kevin-coder (sensenran-guzi · GZ-BEAN-023)
 */
@Slf4j
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/system/gz/bean/seat")
public class GzBeanSeatController extends BaseController {

    private final IGzBeanSeatService seatService;

    /** 分页列表 */
    @SaCheckPermission("gz:bean:seat:list")
    @GetMapping("/list")
    public TableDataInfo<GzBeanSeatVO> list(GzBeanSeatQueryBo query, PageQuery pageQuery) {
        return seatService.selectPageList(query, pageQuery);
    }

    /** 全量（按 store_id，不分页 — admin tab 内座位 tab 用） */
    @SaCheckPermission("gz:bean:seat:list")
    @GetMapping("/listByStore/{storeId}")
    public R<List<GzBeanSeatVO>> listByStore(@NotNull @PathVariable Long storeId) {
        GzBeanSeatQueryBo q = new GzBeanSeatQueryBo();
        q.setStoreId(storeId);
        return R.ok(seatService.selectList(q));
    }

    /** 详情 */
    @SaCheckPermission("gz:bean:seat:list")
    @GetMapping("/{id}")
    public R<GzBeanSeatVO> getInfo(@NotNull @PathVariable Long id) {
        GzBeanSeatVO vo = seatService.selectVoById(id);
        if (vo == null) {
            return R.fail("座位不存在：" + id);
        }
        return R.ok(vo);
    }

    /** 新增 */
    @SaCheckPermission("gz:bean:seat:add")
    @Log(title = "拼豆座位", businessType = BusinessType.INSERT)
    @RepeatSubmit()
    @PostMapping
    public R<Void> add(@Validated(AddGroup.class) @RequestBody GzBeanSeatBo bo) {
        return toAjax(seatService.insertByBo(bo) ? 1 : 0);
    }

    /** 编辑（storeId / seatNo 不可改 — service 内部忽略；可改归属桌型 / 分区 / 启停 / 排序） */
    @SaCheckPermission("gz:bean:seat:edit")
    @Log(title = "拼豆座位单元", businessType = BusinessType.UPDATE)
    @RepeatSubmit()
    @PutMapping
    public R<Void> edit(@Validated(EditGroup.class) @RequestBody GzBeanSeatBo bo) {
        return toAjax(seatService.updateByBo(bo) ? 1 : 0);
    }

    /** 启停（0=停用 / 1=启用） */
    @SaCheckPermission("gz:bean:seat:edit")
    @Log(title = "拼豆座位单元启停", businessType = BusinessType.UPDATE)
    @PutMapping("/{id}/enabled")
    public R<Void> toggleEnabled(@NotNull @PathVariable Long id, @RequestParam Integer enabled) {
        return toAjax(seatService.toggleEnabled(id, enabled) ? 1 : 0);
    }

    /** 软删（按 id 集合） */
    @SaCheckPermission("gz:bean:seat:remove")
    @Log(title = "拼豆座位单元", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public R<Void> remove(@NotEmpty @PathVariable Long[] ids) {
        return toAjax(seatService.deleteByIds(List.of(ids)) ? 1 : 0);
    }

    /** 按桌型批量生成座位单元（ADR-0015 §1；whole=桌数 / seat=桌数×每桌座数，幂等复活/跳过） */
    @SaCheckPermission("gz:bean:seat:batchGenerate")
    @Log(title = "拼豆座位单元批量生成", businessType = BusinessType.INSERT)
    @RepeatSubmit()
    @PostMapping("/batchGenerate")
    public R<GzBeanSeatBatchGenerateResultVO> batchGenerate(@Validated @RequestBody GzBeanSeatBatchGenerateBo bo) {
        GzBeanSeatBatchGenerateResultVO result = seatService.batchGenerate(bo);
        // 前缀与已有座位编号跨桌型撞车时，created 可能是 0 —— 必须在 msg 里说清楚，否则店员看到的是
        // 「点了生成但什么都没多」（seat_no 全店唯一，GZ-BEAN-054）
        String msg = Boolean.TRUE.equals(result.getHasConflict())
            ? "生成 / 复活 " + result.getCreated() + " 个座位单元；有 " + result.getConflictSeatNos().size()
                + " 个编号已被其它桌型占用（" + String.join("、", result.getConflictSeatNos()) + "），请更换编号前缀"
            : "成功生成 / 复活 " + result.getCreated() + " 个座位单元（已存在的跳过）";
        return R.ok(msg, result);
    }

}
