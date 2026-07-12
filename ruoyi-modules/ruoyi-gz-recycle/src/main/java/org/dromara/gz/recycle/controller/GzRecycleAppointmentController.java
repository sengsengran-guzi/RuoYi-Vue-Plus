package org.dromara.gz.recycle.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.R;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.common.web.core.BaseController;
import org.dromara.gz.recycle.domain.bo.GzRecycleAppointmentQueryBo;
import org.dromara.gz.recycle.domain.bo.GzRecycleVerifyBo;
import org.dromara.gz.recycle.domain.vo.GzRecycleAppointmentAdminVO;
import org.dromara.gz.recycle.service.IGzRecycleAppointmentService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * GZ-RECYCLE-003 admin 回收预约单管理（plus-ui owner 兜底，AI 终态，走 ruoyi/Element Plus 默认）。
 *
 * <p>路径 {@code /system/gz/recycle/appointment}。menu 13002（RECYCLE-002 已 seed）；owner 全权限。
 * 列表（按门店/日期/状态筛）+ 详情（两套照片 + 估价/实付 + 核对留痕 verified_by/verify_time）+ 失败重试。
 * 核对 / 触发打款主入口在 mp 店员端（ADR-0004），admin 仅查看 + owner 失败兜底重试。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-RECYCLE-003)
 */
@Slf4j
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/system/gz/recycle/appointment")
public class GzRecycleAppointmentController extends BaseController {

    private final IGzRecycleAppointmentService appointmentService;

    /** 分页列表（按门店 / 日期区间 / 状态 / 预约号筛）。 */
    @SaCheckPermission("gz:recycle:appointment:list")
    @GetMapping("/list")
    public TableDataInfo<GzRecycleAppointmentAdminVO> list(GzRecycleAppointmentQueryBo query, PageQuery pageQuery) {
        return appointmentService.selectAdminPage(query, pageQuery);
    }

    /** 详情（两套照片 + 估价/实付 + 核对留痕）。 */
    @SaCheckPermission("gz:recycle:appointment:list")
    @GetMapping("/{id}")
    public R<GzRecycleAppointmentAdminVO> detail(@PathVariable Long id) {
        GzRecycleAppointmentAdminVO vo = appointmentService.getAdminDetail(id);
        if (vo == null) {
            return R.fail("回收预约单不存在");
        }
        return R.ok(vo);
    }

    /**
     * 失败重试触发打款（owner 对 payout_failed 单，doc/10 §13.E4）。
     *
     * <pre>POST /system/gz/recycle/appointment/{id}/retry-payout</pre>
     */
    @SaCheckPermission("gz:recycle:appointment:payout")
    @Log(title = "回收打款失败重试", businessType = BusinessType.UPDATE)
    @PostMapping("/{id}/retry-payout")
    public R<GzRecycleAppointmentAdminVO> retryPayout(@PathVariable Long id) {
        return R.ok(appointmentService.retryPayout(id));
    }

    /**
     * admin 端核销确认 + 触发反向打款（GZ-RECYCLE-009：admin 看板也能核销，与 mp 店员端等价）。
     *
     * <pre>
     * POST /system/gz/recycle/appointment/{id}/verify
     * Body: { verifyImageIds:[..], finalAmountCent, remark? }   // appointmentId 由路径 {id} 绑定
     * </pre>
     *
     * <p>复用与 mp 店员端同一 service {@link IGzRecycleAppointmentService#verifyAndPayout}（service 不绑 mp/admin）。
     * 权限 {@code gz:recycle:appointment:verify}（与 mp 店员同一 key，owner 已授，ADR-0004）；核对人留痕取 admin
     * 登录用户名（沿用本项目 admin 侧 {@code LoginHelper.getUsername()} 口径）。业务错误码同 mp：
     * 4104 不存在 / 4105 非可核对态 / 4106 openid 缺失 + 金额硬上限。</p>
     */
    @SaCheckPermission("gz:recycle:appointment:verify")
    @Log(title = "回收核销+触发打款(admin)", businessType = BusinessType.UPDATE)
    @PostMapping("/{id}/verify")
    public R<GzRecycleAppointmentAdminVO> verify(@PathVariable Long id, @Valid @RequestBody GzRecycleVerifyBo bo) {
        // 路径 id 为准（防 body.appointmentId 与 URL 不一致）
        bo.setAppointmentId(id);
        String verifiedBy = LoginHelper.getUsername();
        return R.ok(appointmentService.verifyAndPayout(bo, verifiedBy));
    }
}
