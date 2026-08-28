package org.dromara.gz.recycle.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.R;
import org.dromara.common.idempotent.annotation.RepeatSubmit;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.common.web.core.BaseController;
import org.dromara.gz.recycle.domain.bo.GzRecycleAppointmentQueryBo;
import org.dromara.gz.recycle.domain.bo.GzRecycleManualHoldBo;
import org.dromara.gz.recycle.domain.bo.GzRecycleRescheduleBo;
import org.dromara.gz.recycle.domain.bo.GzRecycleVerifyBo;
import org.dromara.gz.recycle.domain.vo.GzRecycleAppointmentAdminVO;
import org.dromara.gz.recycle.domain.vo.GzRecycleWeekBoardVO;
import org.dromara.gz.recycle.domain.vo.RecycleSlotAvailabilityVO;
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

    /**
     * 手动占用时段（ADR-0021 §1，代客预约 + 临时关闭时段合一）。
     *
     * <pre>
     * POST /system/gz/recycle/appointment/manual-hold
     * Body: { storeId, apptDate, timeSlotIds:[..], remark }
     * </pre>
     *
     * <p>多格 = 多行，同一事务 all-or-nothing（任一格已被占 → 整批回滚）。</p>
     */
    @SaCheckPermission("gz:recycle:appointment:hold")
    @Log(title = "回收手动占用时段", businessType = BusinessType.INSERT)
    @PostMapping("/manual-hold")
    public R<List<GzRecycleAppointmentAdminVO>> manualHold(@Valid @RequestBody GzRecycleManualHoldBo bo) {
        String operator = LoginHelper.getUsername();
        return R.ok(appointmentService.manualHold(bo, operator));
    }

    /**
     * 释放手动占用（ADR-0021 §1 H4）。
     *
     * <pre>POST /system/gz/recycle/appointment/{id}/release-hold</pre>
     *
     * <p>守卫 {@code source='manual' AND status='manual_hold'}，否则 4129 HOLD_RELEASE_NOT_ALLOWED
     * （顾客单要走取消流程，不经本端点）。</p>
     */
    @SaCheckPermission("gz:recycle:appointment:hold")
    @Log(title = "回收释放手动占用", businessType = BusinessType.UPDATE)
    @PostMapping("/{id}/release-hold")
    public R<GzRecycleAppointmentAdminVO> releaseHold(@PathVariable Long id) {
        return R.ok(appointmentService.releaseHold(id));
    }

    /**
     * 预约改期——同一行原地 UPDATE（ADR-0021 §2）。
     *
     * <pre>
     * POST /system/gz/recycle/appointment/{id}/reschedule
     * Body: { apptDate, timeSlotId }   // 不含 storeId，不允许跨门店改期
     * </pre>
     *
     * <p>适用 {@code submitted} 顾客单 / {@code manual_hold} 手动占用；其余状态 4128 RESCHEDULE_NOT_ALLOWED。</p>
     */
    @SaCheckPermission("gz:recycle:appointment:reschedule")
    @Log(title = "回收预约改期", businessType = BusinessType.UPDATE)
    @PostMapping("/{id}/reschedule")
    public R<GzRecycleAppointmentAdminVO> reschedule(@PathVariable Long id, @Valid @RequestBody GzRecycleRescheduleBo bo) {
        String operator = LoginHelper.getUsername();
        return R.ok(appointmentService.reschedule(id, bo, operator));
    }

    /**
     * 取消顾客单（GZ-RECYCLE-014）：释放它占住的全部小时格。
     *
     * <pre>POST /system/gz/recycle/appointment/{id}/cancel</pre>
     *
     * <p>仅 {@code submitted / confirmed_onsite} 可取消（回收是反向打款，有钱在途/已出账的单一律 4132）。
     * 手动占用走 {@code release-hold}。复用既有 {@code hold} 权限，不新增 menu。</p>
     *
     * <p><b>为什么需要它</b>：prod SnailJob 没部署，no_show cron 从来没跑过 —— 顾客爽约单此前没有任何
     * 释放手段。改小时格后一张 5 小时大单爽约 = 当天 5 个格全废。</p>
     */
    @SaCheckPermission("gz:recycle:appointment:hold")
    @Log(title = "回收预约取消", businessType = BusinessType.UPDATE)
    @RepeatSubmit()
    @PostMapping("/{id}/cancel")
    public R<GzRecycleAppointmentAdminVO> cancelCustomer(@PathVariable Long id) {
        return R.ok(appointmentService.cancelCustomerAppointment(id, LoginHelper.getUsername()));
    }

    /**
     * 回收看板周视图（ADR-0021 §3）。
     *
     * <pre>GET /system/gz/recycle/appointment/week-board?storeId=&weekStart=YYYY-MM-DD</pre>
     *
     * <p>{@code weekStart} 后端归一到所在周的周一（传周三也返回周一起 7 天）。</p>
     */
    @SaCheckPermission("gz:recycle:appointment:list")
    @GetMapping("/week-board")
    public R<GzRecycleWeekBoardVO> weekBoard(@RequestParam Long storeId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate weekStart) {
        return R.ok(appointmentService.selectWeekBoard(storeId, weekStart));
    }

    /**
     * 小时格可用性（GZ-RECYCLE-012 / ADR-0022，改期弹窗选新时间用）。
     *
     * <pre>GET /system/gz/recycle/appointment/hour-slots?storeId=&amp;date=YYYY-MM-DD&amp;spanHours=4&amp;excludeAppointmentId=123</pre>
     *
     * <p>与 mp 的 {@code slot-availability} 同一核心，区别只有两点：{@code spanHours} 直接传数字
     * （admin 已知本单占几小时，不必回查点数档）+ 支持 {@code excludeAppointmentId}。</p>
     *
     * <p><b>{@code excludeAppointmentId} 必传</b>（改期场景）：不排除的话被改期的单会跟自己的原区间冲突，
     * 相邻起点永远选不了。</p>
     */
    @SaCheckPermission("gz:recycle:appointment:list")
    @GetMapping("/hour-slots")
    public R<RecycleSlotAvailabilityVO> hourSlots(@RequestParam Long storeId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) Integer spanHours,
            @RequestParam(required = false) Long excludeAppointmentId) {
        return R.ok(appointmentService.getHourSlotsForAdmin(storeId, date, spanHours, excludeAppointmentId));
    }
}
