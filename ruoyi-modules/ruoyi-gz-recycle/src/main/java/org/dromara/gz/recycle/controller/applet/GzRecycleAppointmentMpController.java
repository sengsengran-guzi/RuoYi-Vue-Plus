package org.dromara.gz.recycle.controller.applet;

import cn.dev33.satoken.annotation.SaIgnore;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.R;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.gz.recycle.domain.bo.GzRecycleAppointmentSubmitBo;
import org.dromara.gz.recycle.domain.vo.GzRecycleAppointmentVO;
import org.dromara.gz.recycle.domain.vo.GzRecycleCategoryVO;
import org.dromara.gz.recycle.domain.vo.GzRecycleQtyRangeVO;
import org.dromara.gz.recycle.domain.vo.GzRecycleTimeSlotVO;
import org.dromara.gz.recycle.domain.vo.RecycleSlotAvailabilityVO;
import org.dromara.gz.recycle.domain.vo.RecycleVerifyCodeVO;
import org.dromara.gz.recycle.service.IGzRecycleAppointmentService;
import org.dromara.gz.recycle.service.IGzRecyclePriceRuleService;
import org.dromara.gz.recycle.service.IGzRecycleQtyRangeService;
import org.dromara.gz.recycle.service.IGzRecycleTimeSlotService;
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
 * mp 端回收预约 Controller（GZ-RECYCLE-007 放开：去 IP / 去拍照 + 点数档 + 时段容量 + 核销码）。
 *
 * <p>路径 {@code /app/gz/recycle/appointment}（mp 前缀 {@code /app/} 与 admin {@code /system/} 区分）。</p>
 *
 * <p>端点：</p>
 * <ul>
 *   <li>{@code GET  /qty-ranges} — 启用点数档单选源（带 durationMinutes + occupyNextSlot）</li>
 *   <li>{@code GET  /time-slots?storeId} — 某门店启用到店时段列表（GZ-RECYCLE-006，按门店可配）</li>
 *   <li>{@code GET  /slot-availability?storeId&date} — 某门店某日各时段可用性（占用置灰，GZ-RECYCLE-007）</li>
 *   <li>{@code GET  /categories} — 可回收品类下拉</li>
 *   <li>{@code POST /submit} — 提交回收预约（点数档 + 时段容量，去 IP / 去拍照，落 submitted）</li>
 *   <li>{@code GET  /my} — 我的回收记录列表（顾客窄 VO 三段）</li>
 *   <li>{@code GET  /{id}} — 我的回收预约详情（仅本人，顾客窄 VO）</li>
 *   <li>{@code GET  /{id}/verify-code} — 取到店核销码（契约 §F.2，仅本人）</li>
 * </ul>
 *
 * <p><b>登录态</b>：只读浏览端点（qty-ranges / time-slots / slot-availability / categories）<b>匿名可读</b>
 * （{@link SaIgnore}，browse-first：回收是落地 tab，游客先浏览点数档 / 时段 / 品类再决定是否预约，仅只读、无个人数据）；
 * 提交 / 我的 / 详情 / 核销码端点仍需登录态（未登录 → 401，userId / openid 由 sa-token 拿），故注解打在方法级。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-RECYCLE-004)
 */
@Slf4j
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/app/gz/recycle/appointment")
public class GzRecycleAppointmentMpController {

    private final IGzRecycleAppointmentService appointmentService;
    private final IGzRecyclePriceRuleService priceRuleService;
    private final IGzRecycleQtyRangeService qtyRangeService;
    private final IGzRecycleTimeSlotService timeSlotService;

    /**
     * 启用点数档列表（mp 填单单选源，GZ-RECYCLE-007 放开，带 durationMinutes + occupyNextSlot）。
     *
     * <pre>
     * GET /app/gz/recycle/appointment/qty-ranges
     * 200 OK { "code":200, "data": [ {"id":"1","code":"pts-1-50","label":"1-50 点","durationMinutes":60,"occupyNextSlot":0,...} ] }
     * </pre>
     *
     * <p>仅返 enabled=1，按 sort_no/id 升序；登录态即可（无新权限）。用户单选点数档 → 提交 qtyBucketCode；
     * durationMinutes = 预计回收时长，occupyNextSlot=1（大单）下单时额外占用下一个到店时段。</p>
     *
     * <p>匿名可读（{@link SaIgnore}，browse-first）：游客浏览点数档所需，仅只读、无个人数据。</p>
     */
    @SaIgnore
    @GetMapping("/qty-ranges")
    public R<List<GzRecycleQtyRangeVO>> qtyRanges() {
        return R.ok(qtyRangeService.listEnabled());
    }

    /**
     * 某门店启用到店时段列表（mp 填单单选源，GZ-RECYCLE-006，按门店可配，取代写死的上午/下午两档）。
     *
     * <pre>
     * GET /app/gz/recycle/appointment/time-slots?storeId=1
     * 200 OK { "code":200, "data": [ {"id":"5","label":"上午","startTime":"10:00:00","endTime":"13:00:00",...} ] }
     * </pre>
     *
     * <p>仅返该门店 {@code enabled=1} 时段，按 sort_no/start_time/id 升序；登录态即可（无新权限）。
     * 用户单选时段 → 提交 timeSlotId；后端按 id 取起止时间落预约单 slot_start/slot_end。
     * storeId 缺省返空列表（前端先选门店再拉时段）。</p>
     *
     * <p>匿名可读（{@link SaIgnore}，browse-first）：游客浏览门店到店时段所需，仅只读、无个人数据。</p>
     */
    @SaIgnore
    @GetMapping("/time-slots")
    public R<List<GzRecycleTimeSlotVO>> timeSlots(@RequestParam(required = false) Long storeId) {
        return R.ok(timeSlotService.listEnabledByStore(storeId));
    }

    /**
     * 某门店某日到店时段可用性（GZ-RECYCLE-007 放开，mp 选时段实时显「可约/已占」）。
     *
     * <pre>
     * GET /app/gz/recycle/appointment/slot-availability?storeId=1&amp;date=2026-08-27&amp;qtyBucketCode=pts-150-200
     * 200 OK { "code":200, "data": { "date":"2026-08-27", "spanHours":4, "slots":[
     *   {"startTime":"10:00:00","endTime":"11:00:00","label":"10:00","taken":false,"past":false,"selectable":true}, ... ] } }
     * </pre>
     *
     * <p>逐个 1 小时格带 {@code taken / past / selectable}（GZ-RECYCLE-012 / ADR-0022）。
     * 占用真源 = 活跃单区间与该格 {@code [gi, gi+1h)} 重叠（每格容量 1）。
     * <b>前端只看 {@code selectable}</b> —— 「从这格起放不放得下 N 小时」的规则在后端。</p>
     *
     * <p>{@code qtyBucketCode} 可空：缺省 N=1（用户还没选点数档时的纯占用视图）；未知 code 降级 N=1
     * 而不抛 4107（本端点匿名可读，抛业务异常会把浏览态用户打断）。storeId / date 缺省 → 空格列表。</p>
     *
     * <p>匿名可读（{@link SaIgnore}，browse-first）：游客浏览时间可用性所需，仅只读占用布尔、无个人数据
     * （与拼豆 type-slots / seat-map 一致口径）。</p>
     */
    @SaIgnore
    @GetMapping("/slot-availability")
    public R<RecycleSlotAvailabilityVO> slotAvailability(
        @RequestParam(required = false) Long storeId,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
        @RequestParam(required = false) String qtyBucketCode) {
        return R.ok(appointmentService.getSlotAvailability(storeId, date, qtyBucketCode));
    }

    /**
     * 可回收品类选项（mp 品类多选下拉）。
     *
     * <pre>
     * GET /app/gz/recycle/appointment/categories
     * 200 OK { "code":200, "data": [ {"value":"card","label":"卡牌"}, {"value":"goods","label":"谷子"} ] }
     * </pre>
     *
     * <p>= 价目表有 enabled 规则的 distinct category + 字典 gz_recycle_category 中文 label。
     * 去估价后价目表停用于估价，但 category 维度仍作品类来源（停用表保留可查）。</p>
     *
     * <p>匿名可读（{@link SaIgnore}，browse-first）：游客浏览可回收品类所需，仅只读、无个人数据。</p>
     */
    @SaIgnore
    @GetMapping("/categories")
    public R<List<GzRecycleCategoryVO>> categories() {
        return R.ok(priceRuleService.listCategories());
    }

    /**
     * 提交回收预约（GZ-RECYCLE-007 放开：去 IP / 去拍照，落 status=submitted）。
     *
     * <pre>
     * POST /app/gz/recycle/appointment/submit
     * Body: { storeId, product:{categories[],qtyBucketCode}, remark?, timeSlotId, apptDate }
     *
     * 200 OK { "code":200, "data": { appointmentNo:"RCY-20260708-000001", matchedDurationMinutes, status:"submitted", ... } }
     *
     * 业务错误（R.code，mp 端按 code 决定 UX）：
     *   4103 OPENID_REQUIRED         → 「请重新授权微信登录后再提交回收」
     *   4125 MOBILE_REQUIRED         → 「请先提供手机号再预约回收」
     *   4107 QTY_BUCKET_INVALID      → 「点数区间无效，请重选」
     *   4108 CATEGORY_REQUIRED       → 「请至少选择一个回收品类」
     *   4124 SLOT_INVALID            → 「到店时段无效或已关闭，请重新选择」
     *   4122 SLOT_TAKEN              → 「该时段已被预约，请换个时段」
     *   4123 SLOT_SPILL_BLOCKED      → 「该点数需连占下一个时段，但下一个时段已被预约」
     *   4126 SLOT_LOCK_BUSY          → 「预约繁忙，请稍后重试」
     * </pre>
     */
    @PostMapping("/submit")
    @Log(title = "回收预约提交(mp)", businessType = BusinessType.INSERT)
    public R<GzRecycleAppointmentVO> submit(@Valid @RequestBody GzRecycleAppointmentSubmitBo bo) {
        Long userId = LoginHelper.getUserId();
        if (userId == null) {
            return R.fail(401, "未登录");
        }
        log.info("[recycle-mp] submit userId={} storeId={} bucket={} timeSlotId={}",
            userId, bo.getStoreId(),
            bo.getProduct() == null ? null : bo.getProduct().getQtyBucketCode(),
            bo.getTimeSlotId());
        return R.ok(appointmentService.submit(bo, userId));
    }

    /**
     * 我当前进行中的回收预约（客户 7.24「一人一单」：回收表单进入前预检，进行中则提示 + 禁止再约）。
     *
     * <pre>
     * GET /app/gz/recycle/appointment/active
     * 200 OK { "code":200, "data": { appointmentNo, status, ... } }  // 有进行中单
     * 200 OK { "code":200, "data": null }                            // 无进行中单，可新预约
     * </pre>
     *
     * <p>「进行中」= submitted / confirmed_onsite / paying / payout_failed（已到账/已取消/已过期释放，可再约）。
     * 登录态必需（未登录 401）；literal /active 优先于 /{id} 路由，不冲突。</p>
     */
    @GetMapping("/active")
    public R<GzRecycleAppointmentVO> active() {
        Long userId = LoginHelper.getUserId();
        if (userId == null) {
            return R.fail(401, "未登录");
        }
        return R.ok(appointmentService.getActiveAppointment(userId));
    }

    /**
     * 我的回收记录列表（按提交时间倒序，顾客窄 VO 三段）。
     */
    @GetMapping("/my")
    public R<List<GzRecycleAppointmentVO>> my() {
        Long userId = LoginHelper.getUserId();
        if (userId == null) {
            return R.fail(401, "未登录");
        }
        return R.ok(appointmentService.selectMyList(userId));
    }

    /**
     * 回收预约详情（仅本人，顾客窄 VO）。
     */
    @GetMapping("/{id}")
    public R<GzRecycleAppointmentVO> detail(@PathVariable Long id) {
        Long userId = LoginHelper.getUserId();
        if (userId == null) {
            return R.fail(401, "未登录");
        }
        GzRecycleAppointmentVO vo = appointmentService.selectMyDetail(id, userId);
        if (vo == null) {
            return R.fail("回收预约不存在或无权查看");
        }
        return R.ok(vo);
    }

    /**
     * 取到店核销码（契约 §F.2，仅本人）。
     *
     * <pre>
     * GET /app/gz/recycle/appointment/{id}/verify-code
     * 200 OK { "code":200, "data": { "qrPayload":"RC|RCY-...|123|1750...|abcd...", "expireEpochSec":1750... } }
     *
     * 业务错误：
     *   4104 APPOINTMENT_NOT_FOUND  → 不存在 / 非本人
     *   4109 QR_NOT_AVAILABLE       → 当前状态不可取码（非 submitted/confirmed_onsite）
     * </pre>
     */
    @GetMapping("/{id}/verify-code")
    public R<RecycleVerifyCodeVO> verifyCode(@PathVariable Long id) {
        Long userId = LoginHelper.getUserId();
        if (userId == null) {
            return R.fail(401, "未登录");
        }
        return R.ok(appointmentService.getVerifyCode(id, userId));
    }
}
