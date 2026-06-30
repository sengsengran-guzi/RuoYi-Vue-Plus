package org.dromara.gz.coupon.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.Valid;
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
import org.dromara.gz.coupon.domain.bo.CouponAudienceConfig;
import org.dromara.gz.coupon.domain.bo.GzCouponIssueBo;
import org.dromara.gz.coupon.domain.bo.GzCouponTemplateBo;
import org.dromara.gz.coupon.domain.bo.GzCouponTemplateQueryBo;
import org.dromara.gz.coupon.domain.vo.GzCouponIssueResultVO;
import org.dromara.gz.coupon.domain.vo.GzCouponTemplateVO;
import org.dromara.gz.coupon.service.IGzCouponIssuanceService;
import org.dromara.gz.coupon.service.IGzCouponTemplateService;
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
 * GZ-COUPON-001 优惠券模板管理 + 批量发放（admin 端）。
 *
 * <p>路径前缀 {@code /system/gz/coupon/template}；与 ruoyi 自带 {@code /system/...} 域名隔离。</p>
 *
 * <p>权限（DDL menu_id 12001 + 按钮 12010~12014，仅 owner role_id=100 授权）：</p>
 * <ul>
 *   <li>{@code gz:coupon:template:list} — 列表 / 详情</li>
 *   <li>{@code gz:coupon:template:add} — 新建</li>
 *   <li>{@code gz:coupon:template:edit} — 编辑 / 暂停 / 启用 / 归档</li>
 *   <li>{@code gz:coupon:template:remove} — 软删</li>
 *   <li>{@code gz:coupon:issue} — 批量发放</li>
 * </ul>
 *
 * <p>状态流转走 pause/activate/archive 端点（doc/11 §11.1 模板态），不允许直接 PUT 改 status。
 * 写操作走 {@code @Log} AOP 写入操作日志。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-COUPON-001)
 */
@Slf4j
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/system/gz/coupon/template")
public class GzCouponTemplateController extends BaseController {

    private final IGzCouponTemplateService templateService;
    private final IGzCouponIssuanceService issuanceService;

    /** 分页查询模板列表（全状态 + 筛选）。 */
    @SaCheckPermission("gz:coupon:template:list")
    @GetMapping("/list")
    public TableDataInfo<GzCouponTemplateVO> list(GzCouponTemplateQueryBo query, PageQuery pageQuery) {
        return templateService.selectPage(query, pageQuery);
    }

    /** 模板详情（编辑页回填）。 */
    @SaCheckPermission("gz:coupon:template:list")
    @GetMapping("/{id}")
    public R<GzCouponTemplateVO> getInfo(@NotNull @PathVariable Long id) {
        GzCouponTemplateVO vo = templateService.selectById(id);
        if (vo == null) {
            return R.fail("券模板不存在：" + id);
        }
        return R.ok(vo);
    }

    /** 新建模板（status=active，template_no 系统生成）。 */
    @SaCheckPermission("gz:coupon:template:add")
    @Log(title = "优惠券模板", businessType = BusinessType.INSERT)
    @RepeatSubmit()
    @PostMapping
    public R<Long> add(@Validated(AddGroup.class) @RequestBody GzCouponTemplateBo bo) {
        return R.ok("新建成功", templateService.insertByBo(bo));
    }

    /** 编辑模板（template_no / status / issuedCount / version 不可改）。 */
    @SaCheckPermission("gz:coupon:template:edit")
    @Log(title = "优惠券模板", businessType = BusinessType.UPDATE)
    @RepeatSubmit()
    @PutMapping
    public R<Void> edit(@Validated(EditGroup.class) @RequestBody GzCouponTemplateBo bo) {
        return toAjax(templateService.updateByBo(bo));
    }

    /** 逻辑删（软删 del_flag='2'）。 */
    @SaCheckPermission("gz:coupon:template:remove")
    @Log(title = "优惠券模板", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public R<Void> remove(@NotEmpty @PathVariable Long[] ids) {
        return toAjax(templateService.deleteByIds(List.of(ids)));
    }

    /** 暂停发放（active → paused）。 */
    @SaCheckPermission("gz:coupon:template:edit")
    @Log(title = "优惠券模板暂停", businessType = BusinessType.UPDATE)
    @RepeatSubmit()
    @PostMapping("/pause/{id}")
    public R<Void> pause(@NotNull @PathVariable Long id) {
        return toAjax(templateService.pause(id));
    }

    /** 重新启用（paused → active）。 */
    @SaCheckPermission("gz:coupon:template:edit")
    @Log(title = "优惠券模板启用", businessType = BusinessType.UPDATE)
    @RepeatSubmit()
    @PostMapping("/activate/{id}")
    public R<Void> activate(@NotNull @PathVariable Long id) {
        return toAjax(templateService.activate(id));
    }

    /** 归档（active/paused → archived）。 */
    @SaCheckPermission("gz:coupon:template:edit")
    @Log(title = "优惠券模板归档", businessType = BusinessType.UPDATE)
    @RepeatSubmit()
    @PostMapping("/archive/{id}")
    public R<Void> archive(@NotNull @PathVariable Long id) {
        return toAjax(templateService.archive(id));
    }

    /** 批量发放（manual 选名单 / filtered 条件筛选；乐观锁防超发，配额耗尽拦截）。 */
    @SaCheckPermission("gz:coupon:issue")
    @Log(title = "优惠券批量发放", businessType = BusinessType.INSERT)
    @RepeatSubmit()
    @PostMapping("/issue")
    public R<GzCouponIssueResultVO> issue(@Valid @RequestBody GzCouponIssueBo bo) {
        return R.ok("发放成功", issuanceService.issue(bo));
    }

    /** 条件筛选「预览命中人数」（ADR-0010）：配置 / 发放前校验 audience，预览口径 = 实发口径。 */
    @SaCheckPermission("gz:coupon:issue")
    @PostMapping("/preview-audience")
    public R<Long> previewAudience(@RequestBody CouponAudienceConfig config) {
        return R.ok(issuanceService.previewAudience(config.getConditions()));
    }

    /**
     * 自动发放「立即试跑」（GZ-COUPON-003）：对单个 filtered + auto_issue 模板手动跑一次自动发放逻辑
     * （按条件圈人 → 去重已持券用户 → 配额乐观锁发剩余），返回本次发放张数。供 admin 验证规则配置是否符合预期，
     * 定时调度逻辑与本端点共用 {@code autoIssueOnce}。
     */
    @SaCheckPermission("gz:coupon:issue")
    @Log(title = "优惠券自动发放试跑", businessType = BusinessType.INSERT)
    @RepeatSubmit()
    @PostMapping("/{id}/auto-issue-once")
    public R<Integer> autoIssueOnce(@NotNull @PathVariable Long id) {
        return R.ok("发放成功", issuanceService.autoIssueOnce(id));
    }
}
