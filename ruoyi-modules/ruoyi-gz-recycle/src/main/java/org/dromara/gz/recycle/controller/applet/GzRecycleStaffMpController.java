package org.dromara.gz.recycle.controller.applet;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.hutool.core.util.StrUtil;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.R;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.gz.common.domain.dto.MpStaffPermission;
import org.dromara.gz.common.service.IMpStaffPermissionService;
import org.dromara.gz.recycle.domain.bo.GzRecycleVerifyBo;
import org.dromara.gz.recycle.domain.bo.GzRecycleVerifyScanBo;
import org.dromara.gz.recycle.domain.vo.GzRecycleAppointmentAdminVO;
import org.dromara.gz.recycle.service.IGzRecycleAppointmentService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * GZ-RECYCLE-003 mp 店员核对 Controller（ADR-0004 mp 管理端权限底座）。
 *
 * <p>路径 {@code /app/gz/recycle/staff}（mp 前缀 {@code /app/}）。绑定店员从「我的（店员端）」门店管理 →
 * 「回收核对」进入：按预约 id 调出预约（看两套照片 / 物品清单 / 估价）→ 拍照 verify_image_ids → 微调
 * final_amount → 确认 → submitted→confirmed_onsite + 触发反向打款（PAY-105，confirmed_onsite→paying）。</p>
 *
 * <p><b>权限</b>：核对端点 {@code @SaCheckPermission("gz:recycle:appointment:verify")}（与 admin 同一权限 key、
 * 同一 ruoyi RBAC，ADR-0004）；调出预约端点 {@code list} 权限（owner+staff）。同租户 1001 由 ruoyi 拦截器兜底；
 * 停用/解绑 sys_user 即时踢 token（ADR-0004 安全红线）。即便直接进入，纯顾客调端点后端 403。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-RECYCLE-003)
 */
@Slf4j
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/app/gz/recycle/staff")
public class GzRecycleStaffMpController {

    private final IGzRecycleAppointmentService appointmentService;
    private final IMpStaffPermissionService mpStaffPermissionService;

    /**
     * 店员调出预约单（核对前看实物 / 物品清单 / 估价；跨用户）。
     *
     * <pre>
     * GET /app/gz/recycle/staff/appointment/{id}
     * 200 OK { code:200, data: { appointmentNo, status, products, estimatedAmountCent, submitImageIds, ... } }
     * </pre>
     */
    @SaCheckPermission("gz:recycle:appointment:list")
    @GetMapping("/appointment/{id}")
    public R<GzRecycleAppointmentAdminVO> appointmentDetail(@PathVariable Long id) {
        GzRecycleAppointmentAdminVO vo = appointmentService.getAdminDetail(id);
        if (vo == null) {
            return R.fail("回收预约单不存在");
        }
        return R.ok(vo);
    }

    /**
     * 店员当天回收核对列表（全门店、全状态；不分页，一天量小）。
     *
     * <pre>
     * GET /app/gz/recycle/staff/appointments?date=YYYY-MM-DD
     * 200 OK { code:200, data:[ { appointmentNo, storeName, status, slotStart, slotEnd, finalAmountCent, ... }, ... ] }
     * </pre>
     *
     * <p>当天（{@code appt_date = date}）全部门店、全部状态的回收预约（租户 1001 由 ruoyi 自动注入，不显式过滤门店）；
     * 按 slot_start 升序（null 排最后）再按 id 升序。{@code date} 必填，非法格式走 ruoyi 常规参数校验（400，不 500）。</p>
     */
    @SaCheckPermission("gz:recycle:appointment:list")
    @GetMapping("/appointments")
    public R<List<GzRecycleAppointmentAdminVO>> appointmentsByDate(
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return R.ok(appointmentService.listStaffByDate(date));
    }

    /**
     * 店员核对确认 + 触发反向打款（doc/10 §13.N8/N9）。
     *
     * <pre>
     * POST /app/gz/recycle/staff/verify
     * Body: { appointmentId, verifyImageIds:[..], finalAmountCent }
     * 200 OK { code:200, data: { status:"paying", outPayoutNo:"PAYOUT-...", finalAmountCent, verifiedBy, verifyTime } }
     *
     * 业务错误（R.code）：
     *   4104 APPOINTMENT_NOT_FOUND   → 「回收预约单不存在」
     *   4105 NOT_VERIFIABLE          → 「该预约单当前状态不可核对」（并发已核对 / 已取消 / 已过期）
     *   4106 PAYOUT_OPENID_MISSING   → 「用户收款 openid 缺失，无法打款」
     * </pre>
     */
    @SaCheckPermission("gz:recycle:appointment:verify")
    @Log(title = "回收核对+触发打款(mp店员)", businessType = BusinessType.UPDATE)
    @PostMapping("/verify")
    public R<GzRecycleAppointmentAdminVO> verify(@Valid @RequestBody GzRecycleVerifyBo bo) {
        Long userId = LoginHelper.getUserId();
        if (userId == null) {
            return R.fail(401, "未登录");
        }
        // 核对人留痕：取绑定 sys_user 显示名（ADR-0004），无则回退 mp 用户名
        String verifiedBy = resolveStaffName(userId);
        log.info("[recycle-staff] verify appointmentId={} finalAmountCent={} verifiedBy={} verifyImages={}",
            bo.getAppointmentId(), bo.getFinalAmountCent(), verifiedBy,
            bo.getVerifyImageIds() == null ? 0 : bo.getVerifyImageIds().size());
        return R.ok(appointmentService.verifyAndPayout(bo, verifiedBy));
    }

    /**
     * 店员扫核销码定位预约（契约 §F.3，核销不限本店）。
     *
     * <pre>
     * POST /app/gz/recycle/staff/verify-scan
     * Body: { qrPayload:"RC|RCY-...|123|1750...|abcd..." }
     * 200 OK { code:200, data: { appointmentNo, status, product, ... } }  // 全量 AdminVO，店员据此进核对
     *
     * 业务错误（R.code）：
     *   4110 QR_PAYLOAD_MALFORMED   → 「核销码格式无法识别」
     *   4111 QR_EXPIRED             → 「核销码已过期」
     *   4112 QR_SIGNATURE_INVALID   → 「核销码无效或已被篡改」
     *   4104 APPOINTMENT_NOT_FOUND  → 「回收预约单不存在」
     * </pre>
     */
    @SaCheckPermission("gz:recycle:appointment:verify")
    @Log(title = "回收核销扫码定位(mp店员)", businessType = BusinessType.OTHER)
    @PostMapping("/verify-scan")
    public R<GzRecycleAppointmentAdminVO> verifyScan(@Valid @RequestBody GzRecycleVerifyScanBo bo) {
        Long userId = LoginHelper.getUserId();
        if (userId == null) {
            return R.fail(401, "未登录");
        }
        log.info("[recycle-staff] verify-scan userId={}", userId);
        return R.ok(appointmentService.verifyScan(bo));
    }

    /** 取核对店员显示名（绑定 sys_user nick_name；兜底 mp username）。 */
    private String resolveStaffName(Long userId) {
        MpStaffPermission perm = mpStaffPermissionService.resolveByGzUserId(userId);
        if (perm.isStaff() && StrUtil.isNotBlank(perm.getStaffName())) {
            return perm.getStaffName();
        }
        String username = LoginHelper.getUsername();
        return StrUtil.isNotBlank(username) ? username : ("mp-user-" + userId);
    }
}
