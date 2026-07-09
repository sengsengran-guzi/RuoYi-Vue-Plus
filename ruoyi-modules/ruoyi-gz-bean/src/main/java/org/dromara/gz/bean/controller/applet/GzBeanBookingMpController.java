package org.dromara.gz.bean.controller.applet;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaIgnore;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.R;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.gz.bean.domain.bo.GzBeanBookingVerifyScanBo;
import org.dromara.gz.bean.domain.bo.GzBeanDayPassSubmitBo;
import org.dromara.gz.bean.domain.bo.GzBeanPaidBookingSubmitBo;
import org.dromara.gz.bean.domain.vo.GzBeanBookingVO;
import org.dromara.gz.bean.domain.vo.GzBeanBookingGroupVO;
import org.dromara.gz.bean.domain.vo.GzBeanDayPassOptionVO;
import org.dromara.gz.bean.domain.vo.GzBeanPaidSubmitVO;
import org.dromara.gz.bean.domain.vo.GzBeanSeatMapVO;
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
import java.time.LocalTime;
import java.util.List;

/**
 * GZ-BEAN-004 mp 端拼豆预约 Controller。
 *
 * <p>路径 {@code /app/gz/bean/booking}（mp 前缀 {@code /app/} 与 admin {@code /system/} 区分）。</p>
 *
 * <p>端点：</p>
 * <ul>
 *   <li>{@code POST /paid-submit} — 付费区间预约下单（影院选座具体座位区间互斥防超卖 + 计费，GZ-BEAN-024）</li>
 *   <li>{@code GET  /seat-map} — 影院选座可用性（具体座位维度，可订 / 已占，GZ-BEAN-024）</li>
 *   <li>{@code GET  /type-slots} — 选座 1h 格余量（按桌型 × 格，可约 / 已满；mp 029 改后保留兼容）</li>
 *   <li>{@code GET  /my} — 我的预约列表（按 sessDate desc）</li>
 *   <li>{@code GET  /{id}} — 预约详情（仅当前用户）</li>
 *   <li>{@code POST /{id}/cancel} — 用户取消预约（doc/10 §3.N9）</li>
 * </ul>
 *
 * <p><b>登录态</b>：本接口需登录态；sa-token 全局拦截，未登录 → 401。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-BEAN-004 / GZ-BEAN-017)
 */
@Slf4j
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/app/gz/bean/booking")
public class GzBeanBookingMpController {

    private final IGzBeanBookingService bookingService;

    /**
     * 影院选座付费<b>区间</b>预约下单（GZ-BEAN-024，具体座位 + 1h 连续多选，ADR-0015 §2/§3）。
     *
     * <pre>
     * POST /app/gz/bean/booking/paid-submit
     * Body:    { storeId, seatId, sessDate, slotStart, slotEnd, couponId?, dedupClientToken? }
     *          （seatId = 影院图选中的具体座位；slotStart..slotEnd 跨 N 连续 1h 格，如 10:00..13:00 = 3 格）
     *
     * 200 OK（付费单，实付>0）
     * { "code":200, "data": {
     *     "id":"...", "bookingNo":"BK...", "seatType":"st10", "seatTypeSnapshot":"四人共享桌",
     *     "amountCent":4500(=单价×N), "discountAmountCent":0, "payAmountCent":4500,
     *     "payStatus":"paying", "free":false, "outTradeNo":"PINDOU-...",
     *     "payParams": { timeStamp, nonceStr, packageVal, signType, paySign, outTradeNo }
     * } }
     * 200 OK（免费单，实付=0 兜底）：free=true, payStatus="paid", payParams=null（mp 直接跳详情）
     *
     * 业务错误（R.code）：
     *   4001 PHONE_REQUIRED          → 弹手机号授权
     *   4002 SEAT_TAKEN              → 「该座位该时段已被预约」（具体座位区间互斥，ADR-0015 §2；座不存在/不属本店亦此码）
     *   4005 SEAT_DISABLED           → 「该座位已停用，请重选」
     *   4016 SLOT_RANGE_INVALID      → 「所选时段不连续或跨越休息时段」（跳选 / 跨午休 / 含不可约格）
     *   4012 SEAT_TYPE_NOT_CONFIGURED→ 「该座位暂未开放」（座未挂桌型 / 桌型缺失）
     *   4013 SEAT_TYPE_DISABLED      → 「该座位所属桌型已停用」
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
        log.info("[bean-booking-mp] paid-submit userId={} storeId={} seatTypeConfigId={} sessDate={} slotStart={} couponId={}",
            userId, bo.getStoreId(), bo.getSeatTypeConfigId(), bo.getSessDate(), bo.getSlotStart(), bo.getCouponId());
        return R.ok(bookingService.submitPaid(bo, userId));
    }

    /**
     * POST /app/gz/bean/booking/group-submit — 组单预订下单（ADR-0018 §1）
     * 一家带 N 个孩子：一次订 unitCount 个单位（同桌型 + 同区间）→ 拆 N 子单 + 1 组，支付一次挂组。
     * 组单不用券、不吃前 N 名免费促销（全价）。防超卖 = 逐格配额原子扣 N 份，任一格不足 → 4011 QUOTA_FULL。
     */
    @PostMapping("/group-submit")
    @Log(title = "拼豆组单预订下单(mp)", businessType = BusinessType.INSERT)
    public R<GzBeanPaidSubmitVO> groupSubmit(@Valid @RequestBody org.dromara.gz.bean.domain.bo.GzBeanPaidGroupSubmitBo bo) {
        Long userId = LoginHelper.getUserId();
        if (userId == null) {
            return R.fail(401, "未登录");
        }
        log.info("[bean-booking-mp] group-submit userId={} storeId={} seatTypeConfigId={} sessDate={} slot={}-{} n={}",
            userId, bo.getStoreId(), bo.getSeatTypeConfigId(), bo.getSessDate(), bo.getSlotStart(), bo.getSlotEnd(), bo.getUnitCount());
        return R.ok(bookingService.submitPaidGroup(bo, userId));
    }

    /**
     * 包天套餐下单（GZ-BEAN-042 / ADR-0017）。
     *
     * <pre>
     * POST /app/gz/bean/booking/day-pass-submit
     * Body:    { storeId, seatTypeConfigId, sessDate, dedupClientToken? }   （无时段、无券 —— 全天 + 固定价）
     *
     * 200 OK（付费单）：{ ...GzBeanPaidSubmitVO，amountCent=固定包天价，payParams=五参... }
     *
     * 业务错误（R.code，mp 端按 code 提示）：
     *   4001 PHONE_REQUIRED          → 弹授权手机号
     *   4012 SEAT_TYPE_NOT_CONFIGURED / 4013 SEAT_TYPE_DISABLED
     *   4025 DAY_PASS_NOT_OPEN        → 「该桌型暂未开放包天」
     *   4024 DAY_PASS_FULL            → 「今日包天名额已满」
     *   4011 QUOTA_FULL               → 「该桌型当日已约满」（含小时单占用）
     *   4016 SLOT_RANGE_INVALID       → 「当日无营业时段」
     *   4003 DUPLICATE_USER_BOOKING   → 「您当日该桌型已有预约」
     *   4004 SUBMIT_TOO_FAST          → 「操作过快」
     * </pre>
     */
    @PostMapping("/day-pass-submit")
    @Log(title = "拼豆包天套餐下单(mp)", businessType = BusinessType.INSERT)
    public R<GzBeanPaidSubmitVO> dayPassSubmit(@Valid @RequestBody GzBeanDayPassSubmitBo bo) {
        Long userId = LoginHelper.getUserId();
        if (userId == null) {
            return R.fail(401, "未登录");
        }
        log.info("[bean-booking-mp] day-pass-submit userId={} storeId={} seatTypeConfigId={} sessDate={}",
            userId, bo.getStoreId(), bo.getSeatTypeConfigId(), bo.getSessDate());
        return R.ok(bookingService.submitDayPass(bo, userId));
    }

    /**
     * 包天可用性查询（GZ-BEAN-042 / ADR-0017）。
     *
     * <pre>
     * GET /app/gz/bean/booking/day-pass-options?storeId=1&sessDate=2026-07-06
     * 200 OK { "code":200, "data": [
     *   { "seatTypeConfigId":"...","name":"单人","bookMode":"whole",
     *     "dayPassPriceCent":8000,"dayPassPriceYuan":80.00,"full":false },
     *   ...（每个开放包天且启用的桌型档一档；full=true → 「已满」灰显）
     * ] }
     * 只给 full 布尔，不下发剩余名额数字（对齐 type-slots 铁律）。
     * </pre>
     */
    @SaIgnore
    @GetMapping("/day-pass-options")
    public R<List<GzBeanDayPassOptionVO>> dayPassOptions(
        @RequestParam Long storeId,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate sessDate) {
        return R.ok(bookingService.selectDayPassOptions(storeId, sessDate));
    }

    /**
     * V1.2 选座 1h 格余量查询（GZ-BEAN-017，doc/15a §A.1）。
     *
     * <pre>
     * GET /app/gz/bean/booking/type-slots?storeId=1&sessDate=2026-06-20
     * 200 OK { "code":200, "data": [
     *   { "seatType":"single","name":"单人","unitPriceCent":1500,
     *     "slotStart":"10:00:00","slotEnd":"11:00:00","full":false,"active":true },
     *   ...（每个 (座位类型, 1h 整点格) 一档；午休那格不返）
     * ] }
     * 每档：full=false → 「可约」可点；full=true → 「已满」灰显不可点。不下发余量数字（doc/15a §A.1 铁律）。
     * </pre>
     */
    // 匿名可读：拼豆落地页游客浏览「座位类型×时段」可约/价格所需（browse-first，与门店/时段列表一致）；
    // 仅只读可用性，不含个人数据。本类其余端点（提交/我的/取消/店员/核销）仍需登录态，故注解打在方法级。
    @SaIgnore
    @GetMapping("/type-slots")
    public R<List<GzBeanTypeSlotAvailabilityVO>> typeSlots(
        @RequestParam Long storeId,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate sessDate) {
        return R.ok(bookingService.selectTypeSlotAvailability(storeId, sessDate));
    }

    /**
     * 影院选座可用性（GZ-BEAN-024，ADR-0015 §3 / doc/11 §3.4「可用性接口 VO」）。
     *
     * <pre>
     * GET /app/gz/bean/booking/seat-map?storeId=1&sessDate=2026-06-20&slotStart=10:00:00&slotEnd=13:00:00
     * 200 OK { "code":200, "data": [
     *   { "seatId":"...","seatNo":"Q1-1","tableNo":"Q1","zone":"靠窗区","seatTypeConfigId":"...",
     *     "typeName":"四人共享桌","bookMode":"seat","unitPriceCent":1500,"full":false },
     *   ...（每个启用且挂桌型的座位单元一档；legacy config-less 座不返）
     * ] }
     * full=false → 可订高亮；full=true → 已占灰显不可点。提交 paid-submit 用 seatId + slotStart/slotEnd。
     * slotStart/slotEnd 任一为空 → 仅返回座位布局（full 恒 false，供选区间前预览影院图）。
     * </pre>
     *
     * <p>匿名可读（browse-first，与门店/时段列表一致）：拼豆落地页游客浏览座位图所需，仅只读可用性，
     * 不含个人数据。区间已选时 service 内复用下单同款连续性校验（不连续/跨午休/含不可约格 → SLOT_RANGE_INVALID）。</p>
     */
    @SaIgnore
    @GetMapping("/seat-map")
    public R<List<GzBeanSeatMapVO>> seatMap(
        @RequestParam Long storeId,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate sessDate,
        @RequestParam(required = false) @DateTimeFormat(pattern = "HH:mm:ss") LocalTime slotStart,
        @RequestParam(required = false) @DateTimeFormat(pattern = "HH:mm:ss") LocalTime slotEnd) {
        return R.ok(bookingService.selectSeatMap(storeId, sessDate, slotStart, slotEnd));
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
     * 组单详情（ADR-0018 §1 客户 7.07「多人单显示为一笔」，A 档）：按组 PK 查组头 + N 子单聚合，仅本人可看。
     * mp 组单支付后跳本页（groupId = submitPaidGroup 返回 vo.id），修复过去拿组 PK 当子单 PK 查报「预约不存在」。
     */
    @GetMapping("/group/{groupId}")
    public R<GzBeanBookingGroupVO> groupDetail(@PathVariable Long groupId) {
        Long userId = LoginHelper.getUserId();
        if (userId == null) {
            return R.fail(401, "未登录");
        }
        GzBeanBookingGroupVO vo = bookingService.selectGroupDetailVo(groupId, userId);
        if (vo == null) {
            return R.fail("预约不存在");
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
     * 用户放弃支付 → 立即关单释放座位配额（mp 下单后取消微信支付浮层时调，不等 5min 超时 job）。
     *
     * <p>复用 {@code closeUnpaid}：race-safe 条件 UPDATE（{@code pay_status unpaid/paying → pay_closed} +
     * {@code status pending → cancelled}），真实回调先到已 paid 则幂等跳过返 false（绝不关已付款单 = 不漏退款）。
     * 所有权在 controller 校验（同 cancel）。前端 fire-and-forget，关失败由超时 job 兜底。</p>
     *
     * @return true = 已关单释放 / false = 已非 unpaid/paying（已付款 / 已关闭，幂等跳过）
     */
    @PostMapping("/{id}/close-unpaid")
    public R<Boolean> closeUnpaid(@PathVariable Long id) {
        Long userId = LoginHelper.getUserId();
        if (userId == null) {
            return R.fail(401, "未登录");
        }
        GzBeanBookingVO existed = bookingService.selectVoById(id);
        if (existed == null) {
            return R.fail("预约不存在");
        }
        if (!String.valueOf(userId).equals(String.valueOf(existed.getUserId()))) {
            log.warn("[bean-booking-mp] close-unpaid forbidden userId={} but booking.userId={}", userId, existed.getUserId());
            return R.fail(403, "无权操作该预约");
        }
        return R.ok(bookingService.closeUnpaid(id, String.valueOf(userId)));
    }

    /**
     * 组单用户放弃支付 → 关组释放配额（ADR-0018 §1；mp 组单下单后取消微信支付浮层时调）。
     * 级联全子单 pay_closed + cancelled；所有权在 service 内校验（非本人静默 false）。前端 fire-and-forget。
     */
    @PostMapping("/group/{groupId}/close-unpaid")
    public R<Boolean> closeUnpaidGroup(@PathVariable Long groupId) {
        Long userId = LoginHelper.getUserId();
        if (userId == null) {
            return R.fail(401, "未登录");
        }
        return R.ok(bookingService.closeUnpaidGroup(groupId, String.valueOf(userId)));
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
        log.info("[bean-booking-mp] verify-scan by={} seatId={} payloadLen={}",
            operator, bo.getSeatId(), bo.getQrPayload() == null ? 0 : bo.getQrPayload().length());
        return R.ok(bookingService.verifyByQrPayload(bo.getQrPayload(), bo.getSeatId(), operator));
    }

    /**
     * mp 店员扫码核销前置：扫码预解析（GZ-BEAN-038，ADR-0016 §3 现场分座）。
     *
     * <p>店员扫顾客核销码后先调本端点解析出本单<b>桌型 / 门店 / 日期 / 时段</b>（只读、不改状态，
     * 复用 {@code verifyByQrPayload} 同款解析 + 校签 + status/pay 校验）→ mp 据此调 {@code GET /seat-map}
     * 拉该桌型当前空座给店员手选 → 再带 {@code seatId} 调 {@code POST /verify-scan} 完成核销分座。
     * 已绑座的存量单直接返回其座位，mp 可跳过选座直接核销。</p>
     *
     * <pre>
     * POST /app/gz/bean/booking/scan-resolve   Body: { "qrPayload": "BK|BK...|<verifyCode>" }
     * 200 OK { "code":200, "data": { ...BookingVO，含 seatTypeConfigId/storeId/sessDate/slotStart/slotEnd/seatId... } }
     * 业务错误同 verify-scan：4007/4008/4009/4010/4015(NOT_PAID)（店员不必选完座才发现不能核销）
     * </pre>
     */
    @SaCheckPermission("gz:bean:booking:verify")
    @PostMapping("/scan-resolve")
    public R<GzBeanBookingVO> scanResolve(@Validated @RequestBody GzBeanBookingVerifyScanBo bo) {
        log.info("[bean-booking-mp] scan-resolve by={} payloadLen={}",
            LoginHelper.getUsername(), bo.getQrPayload() == null ? 0 : bo.getQrPayload().length());
        return R.ok(bookingService.resolveByQrPayload(bo.getQrPayload()));
    }
}
