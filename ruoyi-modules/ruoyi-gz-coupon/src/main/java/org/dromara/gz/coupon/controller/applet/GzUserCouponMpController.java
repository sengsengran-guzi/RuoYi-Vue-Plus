package org.dromara.gz.coupon.controller.applet;

import cn.hutool.core.util.StrUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.R;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.gz.coupon.domain.vo.GzUserCouponMpVO;
import org.dromara.gz.coupon.service.IGzUserCouponService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * GZ-COUPON-003 mp 端优惠券 Controller（我的券列表 + 拼豆选券）。
 *
 * <p>路径 {@code /app/gz/coupon}（mp 前缀 {@code /app/} 与 admin {@code /system/} 区分）。</p>
 *
 * <p>端点：</p>
 * <ul>
 *   <li>{@code GET /usable?business=pindou} — 拼豆选券：返当前用户可用券（unused + 适用 + 未过期，
 *       doc/10 §12.N3）。被 mp BizCouponPicker（GZ-BEAN-015 消费）调用。</li>
 *   <li>{@code GET /my?status=} — 我的券分态列表（unused/used/expired 三 tab）。</li>
 * </ul>
 *
 * <p><b>登录态</b>：本接口需登录态；sa-token 全局拦截，未登录 → 401（无需 @SaCheckPermission，mp
 * 用户非 admin 角色，按登录用户 id 做数据归属隔离即可）。ID 全 string（VO ToStringSerializer）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-COUPON-003)
 */
@Slf4j
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/app/gz/coupon")
public class GzUserCouponMpController {

    private final IGzUserCouponService userCouponService;

    /**
     * 拼豆选券：查当前用户可用券（doc/10 §12.N3，AC2/AC3）。
     *
     * <pre>
     * GET /app/gz/coupon/usable?business=pindou&amp;totalAmount=3000
     * Headers: Authorization: Bearer &lt;sa-token&gt; / clientid: mp-applet-sensenran-guzi
     *
     * 200 OK { "code": 200, "data": [ { "id":"12", "couponNo":"UC-...", "templateName":"拼豆满减10元券",
     *          "applicableBusiness":"pindou", "amountSnapshotCent":1000, "status":"unused",
     *          "expireTime":"2026-07-10T00:00:00" } ] }
     * </pre>
     *
     * <p>{@code totalAmount}（单笔金额，分）仅供前端算实付展示，<b>后端不据此过滤券</b>（券面额超额仍可用，
     * 实付下限 0，doc/11 §11.3）；服务端按 unused + applicable_business + 未过期返候选。
     * 真实抵扣锁券在下单 {@code submitPaid} 内二次 CAS 校验（AC4 过期券二次拦截）。</p>
     *
     * @param business    适用业务（默认 pindou，V1 唯一）
     * @param totalAmount 单笔金额（分，可选；仅透传供前端算实付，后端不过滤）
     */
    @GetMapping("/usable")
    public R<List<GzUserCouponMpVO>> usable(
        @RequestParam(value = "business", defaultValue = "pindou") String business,
        @RequestParam(value = "totalAmount", required = false) Long totalAmount) {
        Long userId = LoginHelper.getUserId();
        if (userId == null) {
            return R.fail(401, "未登录");
        }
        log.info("[coupon-mp] usable userId={} business={} totalAmount={}", userId, business, totalAmount);
        return R.ok(userCouponService.listUsableForPindou(userId, business));
    }

    /**
     * 我的券分态列表（AC1，mp「我的优惠券」三 tab）。
     *
     * <pre>
     * GET /app/gz/coupon/my?status=unused        （status 空=全部，不含 locked 瞬态）
     * 200 OK { "code": 200, "data": [ { ...GzUserCouponMpVO } ] }
     * </pre>
     *
     * @param status 券态过滤（{@code unused}/{@code used}/{@code expired}，空=全部）；
     *               非法值（如 locked）由 service 兜底（locked 永不返回给用户）
     */
    @GetMapping("/my")
    public R<List<GzUserCouponMpVO>> my(@RequestParam(value = "status", required = false) String status) {
        Long userId = LoginHelper.getUserId();
        if (userId == null) {
            return R.fail(401, "未登录");
        }
        // locked 是下单瞬态，用户列表不可见：前端只传 unused/used/expired，对异常入参（含 locked）兜底为全部
        String safeStatus = StrUtil.isBlank(status) || "locked".equals(status) ? null : status;
        log.info("[coupon-mp] my userId={} status={}", userId, safeStatus);
        return R.ok(userCouponService.listMyCoupons(userId, safeStatus));
    }
}
