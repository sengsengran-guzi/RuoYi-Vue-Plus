package org.dromara.gz.bean.controller.applet;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.R;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.gz.bean.domain.bo.GzBeanBookingSubmitBo;
import org.dromara.gz.bean.domain.vo.GzBeanBookingMpSubmitVO;
import org.dromara.gz.bean.domain.vo.GzBeanBookingVO;
import org.dromara.gz.bean.service.IGzBeanBookingService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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
}
