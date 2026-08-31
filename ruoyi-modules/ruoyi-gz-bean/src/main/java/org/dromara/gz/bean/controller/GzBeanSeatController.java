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
import org.dromara.gz.bean.domain.vo.GzBeanSeatSyncResultVO;
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

    /**
     * 把该桌型的座位单元对齐到配置数量（GZ-BEAN-055）——补齐缺的 + 移除多余的。
     *
     * <p>与 {@code /batchGenerate} 的区别：这里<b>不用填前缀</b>（后端从已有座位反推，接着往下编）
     * 且<b>会做减法</b>。多余座位若还挂着今天及以后的活跃单则保留不删，在 msg 与 VO 里回报编号。</p>
     *
     * <p>沿用 {@code batchGenerate} 权限，不新增 menu：同一件事（维护座位单元目录）的两个入口。</p>
     */
    @SaCheckPermission("gz:bean:seat:batchGenerate")
    @Log(title = "拼豆座位单元同步", businessType = BusinessType.UPDATE)
    @RepeatSubmit()
    @PostMapping("/sync/{seatTypeConfigId}")
    public R<GzBeanSeatSyncResultVO> sync(@NotNull @PathVariable Long seatTypeConfigId) {
        GzBeanSeatSyncResultVO result = seatService.syncSeatUnits(seatTypeConfigId);
        return R.ok(buildSyncMsg(result), result);
    }

    /**
     * 同步结果的人话版。
     *
     * <p>刻意把「删了哪几个」和「哪几个没敢删」都摊开：软删座位在店员眼里就是「凭空少了格子」，
     * 只回一个数字他无法判断这次同步是不是他要的结果。</p>
     */
    private String buildSyncMsg(GzBeanSeatSyncResultVO r) {
        StringBuilder sb = new StringBuilder();
        sb.append("计时格已对齐：").append(r.getBefore()).append(" → ").append(r.getAfter())
            .append("（配置应有 ").append(r.getExpected()).append("）");
        if (r.getCreated() > 0) {
            sb.append("；新增 ").append(r.getCreated()).append(" 个（编号前缀 ").append(r.getPrefix()).append("）");
        }
        if (r.getPruned() > 0) {
            sb.append("；移除 ").append(r.getPruned()).append(" 个（").append(String.join("、", r.getPrunedSeatNos())).append("）");
        }
        if (!r.getBlockedSeatNos().isEmpty()) {
            sb.append("；").append(String.join("、", r.getBlockedSeatNos()))
                .append(" 还挂着预约未移除，请先在看板上改派这些单");
        }
        if (!r.getConflictSeatNos().isEmpty()) {
            sb.append("；").append(String.join("、", r.getConflictSeatNos()))
                .append(" 的编号已被其它桌型占用，未能生成");
        }
        if (r.getDisabled() > 0) {
            sb.append("；另有 ").append(r.getDisabled()).append(" 个座位已停用，不出现在看板上");
        }
        // 兜底：数量仍没对上、且上面几条都没解释原因 → 绝不能只报一句「已对齐」让人以为完事了
        // （GZ-BEAN-055 实测踩过：复活分支失效时同步静默什么都没做，msg 却是「2 → 2」看着像成功）
        if (!r.getAfter().equals(r.getExpected())
            && r.getBlockedSeatNos().isEmpty() && r.getConflictSeatNos().isEmpty()) {
            sb.append("；⚠️ 仍与配置数量不符，请到「座位单元」页检查该桌型的座位");
        }
        return sb.toString();
    }
}
