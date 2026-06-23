package org.dromara.gz.recycle.controller.applet;

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
import org.dromara.gz.recycle.domain.vo.GzRecycleIpVO;
import org.dromara.gz.recycle.domain.vo.GzRecycleQtyRangeVO;
import org.dromara.gz.recycle.domain.vo.RecycleVerifyCodeVO;
import org.dromara.gz.recycle.service.IGzRecycleAppointmentService;
import org.dromara.gz.recycle.service.IGzRecycleIpService;
import org.dromara.gz.recycle.service.IGzRecyclePriceRuleService;
import org.dromara.gz.recycle.service.IGzRecycleQtyRangeService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * mp 端回收预约 Controller（ADR-0012：去估价 + 单份多选 + 桶 + IP + 核销码）。
 *
 * <p>路径 {@code /app/gz/recycle/appointment}（mp 前缀 {@code /app/} 与 admin {@code /system/} 区分）。</p>
 *
 * <p>端点：</p>
 * <ul>
 *   <li>{@code GET  /ips} — 启用 IP 多选源（契约 §C.2）</li>
 *   <li>{@code GET  /qty-ranges} — 启用数量桶单选源（契约 §C.2，带 durationMinutes）</li>
 *   <li>{@code GET  /categories} — 可回收品类下拉</li>
 *   <li>{@code POST /submit} — 提交回收预约（单份多选，去估价，落 submitted）</li>
 *   <li>{@code GET  /my} — 我的回收记录列表（顾客窄 VO 三段）</li>
 *   <li>{@code GET  /{id}} — 我的回收预约详情（仅本人，顾客窄 VO）</li>
 *   <li>{@code GET  /{id}/verify-code} — 取到店核销码（契约 §F.2，仅本人）</li>
 * </ul>
 *
 * <p><b>登录态</b>：本接口需登录态；sa-token 全局拦截，未登录 → 401。userId / openid 由 sa-token 拿。</p>
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
    private final IGzRecycleIpService ipService;
    private final IGzRecycleQtyRangeService qtyRangeService;

    /**
     * 启用 IP 列表（mp 填单多选源，契约 15a §C.2 / ADR-0012 §4）。
     *
     * <pre>
     * GET /app/gz/recycle/appointment/ips
     * 200 OK { "code":200, "data": [ {"id":"3","ipName":"火影",...}, {"id":"7","ipName":"海贼王",...} ] }
     * </pre>
     *
     * <p>仅返 enabled=1，按 sort_no/id 升序；登录态即可（无新权限）。用户从此列表多选 → 提交 ipIds；
     * 不在列表的走 customIps 自由文本（与列表项并存）。</p>
     */
    @GetMapping("/ips")
    public R<List<GzRecycleIpVO>> ips() {
        return R.ok(ipService.listEnabled());
    }

    /**
     * 启用数量桶列表（mp 填单单选源，契约 15a §C.2 / ADR-0012 §3，带 durationMinutes）。
     *
     * <pre>
     * GET /app/gz/recycle/appointment/qty-ranges
     * 200 OK { "code":200, "data": [ {"id":"1","code":"1-25","label":"1-25 件","durationMinutes":30,...} ] }
     * </pre>
     *
     * <p>仅返 enabled=1，按 sort_no/id 升序；登录态即可（无新权限）。用户单选桶 → 提交 qtyBucketCode；
     * 该桶 durationMinutes = 预计回收时长，提交时后端按 code 查表落 matched_duration_minutes。</p>
     */
    @GetMapping("/qty-ranges")
    public R<List<GzRecycleQtyRangeVO>> qtyRanges() {
        return R.ok(qtyRangeService.listEnabled());
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
     */
    @GetMapping("/categories")
    public R<List<GzRecycleCategoryVO>> categories() {
        return R.ok(priceRuleService.listCategories());
    }

    /**
     * 提交回收预约（ADR-0012 §2，单份多选 + 去估价，落 status=submitted）。
     *
     * <pre>
     * POST /app/gz/recycle/appointment/submit
     * Body: { storeId, product:{categories[],ipIds[],customIps[],qtyBucketCode}, remark?, imageIds:[..], arrivalSlot, apptDate }
     *
     * 200 OK { "code":200, "data": { appointmentNo:"RCY-20260622-000001", matchedDurationMinutes, status:"submitted", ... } }
     *
     * 业务错误（R.code，mp 端按 code 决定 UX）：
     *   4101 SUBMIT_IMAGE_REQUIRED   → 「请先拍照上传实物再提交」（前端已先拦截，后端兜底）
     *   4103 OPENID_REQUIRED         → 「请重新授权微信登录后再提交回收」
     *   4107 QTY_BUCKET_INVALID      → 「数量区间无效，请重选」
     *   4108 CATEGORY_REQUIRED       → 「请至少选择一个回收品类」
     * </pre>
     */
    @PostMapping("/submit")
    @Log(title = "回收预约提交(mp)", businessType = BusinessType.INSERT)
    public R<GzRecycleAppointmentVO> submit(@Valid @RequestBody GzRecycleAppointmentSubmitBo bo) {
        Long userId = LoginHelper.getUserId();
        if (userId == null) {
            return R.fail(401, "未登录");
        }
        log.info("[recycle-mp] submit userId={} storeId={} bucket={} images={}",
            userId, bo.getStoreId(),
            bo.getProduct() == null ? null : bo.getProduct().getQtyBucketCode(),
            bo.getImageIds() == null ? 0 : bo.getImageIds().size());
        return R.ok(appointmentService.submit(bo, userId));
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
