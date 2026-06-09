package org.dromara.gz.bean.controller.applet;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.R;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.gz.bean.domain.bo.GzBeanBookingSubmitBo;
import org.dromara.gz.bean.domain.bo.GzBeanBookingVerifyScanBo;
import org.dromara.gz.bean.domain.bo.GzBeanPaidBookingSubmitBo;
import org.dromara.gz.bean.domain.vo.GzBeanBookingMpSubmitVO;
import org.dromara.gz.bean.domain.vo.GzBeanBookingVO;
import org.dromara.gz.bean.domain.vo.GzBeanPaidSubmitVO;
import org.dromara.gz.bean.domain.vo.GzBeanStaffOverviewVO;
import org.dromara.gz.bean.domain.vo.GzBeanTypeSlotAvailabilityVO;
import org.dromara.gz.bean.service.IGzBeanBookingService;
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
 * GZ-BEAN-004 mp 端拼豆预约 Controller。
 *
 * <p>路径 {@code /app/gz/bean/booking}（mp 前缀 {@code /app/} 与 admin {@code /system/} 区分）。</p>
 *
 * <p>端点：</p>
 * <ul>
 *   <li>{@code POST /submit} — 提交预约（三层防并发 + 核销码生成，doc/10 §3.N7）</li>
 *   <li>{@code GET  /my} — 我的预约列表（按 sessDate desc）</li>
 *   <li>{@code GET  /{id}} — 预约详情（仅当前用户）</li>
 *   <li>{@code POST /{id}/cancel} — 用户取消预约（doc/10 §3.N9）</li>
 * </ul>
 *
 * <p><b>登录态</b>：本接口需登录态；sa-token 全局拦截，未登录 → 401。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-BEAN-004)
 */
@Slf4j
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/app/gz/bean/booking")
public class GzBeanBookingMpController {

    private final IGzBeanBookingService bookingService;

    /**
     * 提交预约（doc/10 §3.N7）。
     *
     * <pre>
     * POST /app/gz/bean/booking/submit
     * Headers: Authorization: Bearer &lt;sa-token&gt; / clientid: mp-applet-sensenran-guzi
     * Body:    { storeId, seatId, sessDate, slotStart, slotEnd, dedupClientToken }
     *
     * 200 OK
     * {
     *   "code": 200,
     *   "data": {
     *     "id": "12345",
     *     "bookingNo": "BK20260601000001",
     *     "seatId": "1",
     *     "seatNoSnapshot": "A1",
     *     "sessDate": "2026-06-01",
     *     "slotStart": "10:00:00",
     *     "slotEnd": "12:00:00",
     *     "verifyCode": "a1b2c3...（32 位 hex）",
     *     "qrPayload": "BK|BK20260601000001|a1b2c3..."
     *   }
     * }
     *
     * 业务错误（R.code，mp 端按 code 决定 UX）：
     * - 4001 PHONE_REQUIRED → 弹手机号授权
     * - 4002 SEAT_TAKEN → toast「座位已被预约，请重选」+ 自动回退选座页
     * - 4003 DUPLICATE_USER_BOOKING → toast「您该时段已有预约」
     * - 4004 SUBMIT_TOO_FAST → toast「操作过快」
     * - 4005 SEAT_DISABLED → toast「该座位已停用」
     * </pre>
     */
    @PostMapping("/submit")
    public R<GzBeanBookingMpSubmitVO> submit(@Valid @RequestBody GzBeanBookingSubmitBo bo) {
        Long userId = LoginHelper.getUserId();
        if (userId == null) {
            return R.fail(401, "未登录");
        }
        log.info("[bean-booking-mp] submit userId={} storeId={} seatId={} sessDate={} slotStart={} dedupClientToken={}",
            userId, bo.getStoreId(), bo.getSeatId(), bo.getSessDate(), bo.getSlotStart(), bo.getDedupClientToken());
        GzBeanBookingMpSubmitVO vo = bookingService.submit(bo, userId);
        return R.ok(vo);
    }

    /**
     * V1.2 付费预约下单（GZ-BEAN-014，单笔单时段，doc/10 §11.N7）。
     *
     * <pre>
     * POST /app/gz/bean/booking/paid-submit
     * Body:    { storeId, seatType, sessDate, slotStart, slotEnd, couponId?, dedupClientToken? }
     *
     * 200 OK（付费单，实付>0）
     * { "code":200, "data": {
     *     "id":"...", "bookingNo":"BK...", "seatType":"single", "seatTypeSnapshot":"单人",
     *     "amountCent":1500, "discountAmountCent":0, "payAmountCent":1500,
     *     "payStatus":"paying", "free":false, "outTradeNo":"PINDOU-...",
     *     "payParams": { timeStamp, nonceStr, packageVal, signType, paySign, outTradeNo }
     * } }
     * 200 OK（免费单，实付=0 兜底）：free=true, payStatus="paid", payParams=null（mp 直接跳详情）
     *
     * 业务错误（R.code）：
     *   4001 PHONE_REQUIRED          → 弹手机号授权
     *   4011 QUOTA_FULL              → 「该时段座位已约满」
     *   4012 SEAT_TYPE_NOT_CONFIGURED→ 「该座位类型暂未开放」
     *   4013 SEAT_TYPE_DISABLED      → 「该座位类型已停用」
     *   4014 WECHAT_ID_REQUIRED      → 弹填微信号
     *   4003 DUPLICATE_USER_BOOKING  → 「您该时段已有预约」
     *   4004 SUBMIT_TOO_FAST         → 「操作过快」
     * </pre>
     */
    @PostMapping("/paid-submit")
    @Log(title = "拼豆付费预约下单(mp)", businessType = BusinessType.INSERT)
    public R<GzBeanPaidSubmitVO> paidSubmit(@Valid @RequestBody GzBeanPaidBookingSubmitBo bo) {
        Long userId = LoginHelper.getUserId();
        if (userId == null) {
            return R.fail(401, "未登录");
        }
        log.info("[bean-booking-mp] paid-submit userId={} storeId={} seatType={} sessDate={} slotStart={} couponId={}",
            userId, bo.getStoreId(), bo.getSeatType(), bo.getSessDate(), bo.getSlotStart(), bo.getCouponId());
        return R.ok(bookingService.submitPaid(bo, userId));
    }

    /**
     * V1.2 选座余量查询（GZ-BEAN-014，doc/10 §11.N3/N4）。
     *
     * <pre>
     * GET /app/gz/bean/booking/type-slots?storeId=1&sessDate=2026-06-20
     * 200 OK { "code":200, "data": [
     *   { "seatType":"single","seatTypeName":"单人","priceCent":1500,
     *     "slotStart":"10:00:00","slotEnd":"12:00:00","quantity":8,"activeCount":3,"remaining":5,"full":false },
     *   ...
     * ] }
     * 每档：remaining>0 → 「还剩 N 个单人座」；remaining≤0（full=true）→ 「已满」灰显。
     * </pre>
     */
    @GetMapping("/type-slots")
    public R<List<GzBeanTypeSlotAvailabilityVO>> typeSlots(
        @RequestParam Long storeId,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate sessDate) {
        return R.ok(bookingService.selectTypeSlotAvailability(storeId, sessDate));
    }

    /**
     * 我的预约列表（默认按 sessDate / slotStart desc，doc/10 §3 booking-list 入口）。
     *
     * @param status 可选状态筛选（pending / used / cancelled / no_show）
     */
    @GetMapping("/my")
    public R<List<GzBeanBookingVO>> my(@RequestParam(required = false) String status) {
        Long userId = LoginHelper.getUserId();
        if (userId == null) {
            return R.fail(401, "未登录");
        }
        return R.ok(bookingService.selectMyMpList(userId, status));
    }

    /**
     * mp 店员/管理者经营概览（GZ-BEAN-008 扩展 — 小程序「预约看板」）。
     *
     * <p>给绑定店员（owner/staff）在小程序看全店预约心里有数：4 个数 + 待到店列表。
     * <b>权限</b>：{@code gz:bean:booking:list}（与 admin 列表同一 key，已授 owner+staff）。
     * 纯顾客 app_user token 不带此权限 → sa-token 403。</p>
     *
     * <pre>
     * GET /app/gz/bean/booking/staff/overview
     * 200 OK { "code":200, "data": { todayTotal, todayPending, todayUsed, upcomingPending, pendingList:[...] } }
     * </pre>
     */
    @SaCheckPermission("gz:bean:booking:list")
    @GetMapping("/staff/overview")
    public R<GzBeanStaffOverviewVO> staffOverview() {
        Long userId = LoginHelper.getUserId();
        if (userId == null) {
            return R.fail(401, "未登录");
        }
        return R.ok(bookingService.selectStaffOverview(userId));
    }

    /**
     * 预约详情（仅当前用户）。
     */
    @GetMapping("/{id}")
    public R<GzBeanBookingVO> detail(@PathVariable Long id) {
        Long userId = LoginHelper.getUserId();
        if (userId == null) {
            return R.fail(401, "未登录");
        }
        GzBeanBookingVO vo = bookingService.selectVoById(id);
        if (vo == null) {
            return R.fail("预约不存在");
        }
        // 权限校验：仅本人可看
        if (!String.valueOf(userId).equals(String.valueOf(vo.getUserId()))) {
            log.warn("[bean-booking-mp] detail forbidden userId={} but booking.userId={}", userId, vo.getUserId());
            return R.fail(403, "无权查看该预约");
        }
        return R.ok(vo);
    }

    /**
     * 用户取消预约（doc/10 §3.N9）。
     */
    @PostMapping("/{id}/cancel")
    public R<GzBeanBookingVO> cancel(@PathVariable Long id) {
        Long userId = LoginHelper.getUserId();
        if (userId == null) {
            return R.fail(401, "未登录");
        }
        // 校验所有权（不能取消别人的预约）
        GzBeanBookingVO existed = bookingService.selectVoById(id);
        if (existed == null) {
            return R.fail("预约不存在");
        }
        if (!String.valueOf(userId).equals(String.valueOf(existed.getUserId()))) {
            log.warn("[bean-booking-mp] cancel forbidden userId={} but booking.userId={}", userId, existed.getUserId());
            return R.fail(403, "无权取消该预约");
        }
        GzBeanBookingVO vo = bookingService.cancel(id, "user", String.valueOf(userId));
        return R.ok(vo);
    }

    /**
     * mp 店员扫码核销（GZ-BEAN-011 / ADR-0004 决策 2）。
     *
     * <p>店员在 mp 个人中心「管理」区「核销」→ {@code wx.scanCode} 扫顾客预约码（payload
     * {@code "BK|{bookingNo}|{verifyCode}"}）→ 调本端点。<b>复用</b> admin 同款
     * {@code GzBeanBookingServiceImpl#verifyByQrPayload}（解析 + HMAC 校签 + pending→used），
     * 不复制业务逻辑（CLAUDE.md 逻辑复用纪律）。</p>
     *
     * <p><b>权限</b>：{@code @SaCheckPermission("gz:bean:booking:verify")} —— 与 admin 同一权限 key、
     * 同一套 ruoyi RBAC（已授 owner+staff）。纯顾客 app_user token 不带此权限 → sa-token 拦截 403
     * （ADR-0004 底座：GZ-SYS-007 登录时把绑定店员的权限装进 app_user 会话）。</p>
     *
     * <pre>
     * POST /app/gz/bean/booking/verify-scan
     * Headers: Authorization / clientid: mp-applet-sensenran-guzi
     * Body:    { "qrPayload": "BK|BK20260601000001|a1b2c3..." }
     *
     * 200 OK   { "code": 200, "data": { ...核销后 VO，status=used... } }
     * 业务错误（R.code，mp 端按 code 决定提示）：
     *   4007 BOOKING_NOT_FOUND       → 「无效核销码（预约不存在）」
     *   4008 INVALID_STATUS          → 「该预约已核销 / 已取消 / 已过期」（msg 含当前状态）
     *   4009 QR_PAYLOAD_MALFORMED    → 「核销码格式无法识别」
     *   4010 QR_SIGNATURE_INVALID    → 「核销码无效或已被篡改」
     * 403（无 verify 权限 / 纯顾客）→ sa-token NotPermissionException
     * </pre>
     */
    @SaCheckPermission("gz:bean:booking:verify")
    @Log(title = "拼豆预约扫码核销(mp)", businessType = BusinessType.UPDATE)
    @PostMapping("/verify-scan")
    public R<GzBeanBookingVO> verifyScan(@Validated @RequestBody GzBeanBookingVerifyScanBo bo) {
        // 核销操作人：app_user username = "wx:{openid}"（落库 verified_by + 审计日志）
        String operator = LoginHelper.getUsername();
        log.info("[bean-booking-mp] verify-scan by={} payloadLen={}",
            operator, bo.getQrPayload() == null ? 0 : bo.getQrPayload().length());
        return R.ok(bookingService.verifyByQrPayload(bo.getQrPayload(), operator));
    }
}
