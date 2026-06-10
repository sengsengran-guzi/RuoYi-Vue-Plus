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
import org.dromara.gz.recycle.domain.vo.GzRecycleEstimateAllVO;
import org.dromara.gz.recycle.service.IGzRecycleAppointmentService;
import org.dromara.gz.recycle.service.IGzRecyclePriceRuleService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * GZ-RECYCLE-002 mp 端回收预约 Controller。
 *
 * <p>路径 {@code /app/gz/recycle/appointment}（mp 前缀 {@code /app/} 与 admin {@code /system/} 区分）。</p>
 *
 * <p>端点：</p>
 * <ul>
 *   <li>{@code POST /estimate} — 多品类累加估价试算（填单实时展示，doc/10 §13.N3）</li>
 *   <li>{@code POST /submit} — 提交回收预约（落 submitted，含 submit_image_ids 必填，doc/10 §13.N5）</li>
 *   <li>{@code GET  /my} — 我的回收记录列表（doc/12 §MP-RECYCLE-LIST）</li>
 *   <li>{@code GET  /{id}} — 我的回收预约详情（仅本人）</li>
 * </ul>
 *
 * <p><b>登录态</b>：本接口需登录态；sa-token 全局拦截，未登录 → 401。userId / openid 由 sa-token 拿。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-RECYCLE-002)
 */
@Slf4j
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/app/gz/recycle/appointment")
public class GzRecycleAppointmentMpController {

    private final IGzRecycleAppointmentService appointmentService;
    private final IGzRecyclePriceRuleService priceRuleService;

    /**
     * 可回收品类选项（doc/12 §MP-RECYCLE-FORM 品类下拉）。
     *
     * <pre>
     * GET /app/gz/recycle/appointment/categories
     * 200 OK { "code":200, "data": [ {"value":"card","label":"卡牌"}, {"value":"goods","label":"谷子"} ] }
     * </pre>
     *
     * <p>= 价目表有 enabled 规则的 distinct category + 字典 gz_recycle_category 中文 label。</p>
     */
    @GetMapping("/categories")
    public R<List<GzRecycleCategoryVO>> categories() {
        return R.ok(priceRuleService.listCategories());
    }

    /**
     * 多品类累加估价试算（doc/10 §13.N3，不落库）。
     *
     * <pre>
     * POST /app/gz/recycle/appointment/estimate
     * Body: { "products": [ {"category":"card","qty":3}, {"category":"goods","qty":5} ] }
     *
     * 200 OK
     * { "code":200, "data": {
     *     "totalQty":8, "estimatedAmountCent":3000, "matchedDurationMinutes":35,
     *     "hasUnpriced":false,
     *     "lines":[ {"category":"card","qty":3,"priced":true,"unitPriceCent":500,"estimatedAmountCent":1500,"matchedDurationMinutes":15}, ... ]
     * } }
     * 某品类未命中区间（E1）→ 该 line.priced=false + hasUnpriced=true（整单不可提交）。
     * </pre>
     */
    @PostMapping("/estimate")
    public R<GzRecycleEstimateAllVO> estimate(@Valid @RequestBody EstimateRequest req) {
        return R.ok(appointmentService.estimateAll(req.getProducts()));
    }

    /**
     * 提交回收预约（doc/10 §13.N5，落 status=submitted）。
     *
     * <pre>
     * POST /app/gz/recycle/appointment/submit
     * Body: { storeId, products:[{category,qty,remark?}], apptDate, slotStart, slotEnd, submitImageIds:[..] }
     *
     * 200 OK { "code":200, "data": { appointmentNo:"RCY-20260622-000001", estimatedAmountCent, matchedDurationMinutes, status:"submitted", ... } }
     *
     * 业务错误（R.code，mp 端按 code 决定 UX）：
     *   4101 SUBMIT_IMAGE_REQUIRED   → 「请先拍照上传实物再提交」（前端已先拦截，后端兜底）
     *   4102 HAS_UNPRICED_CATEGORY   → 「含暂不支持线上估价的品类，请到店咨询」
     *   4103 OPENID_REQUIRED         → 「请重新授权微信登录后再提交回收」
     * </pre>
     */
    @PostMapping("/submit")
    @Log(title = "回收预约提交(mp)", businessType = BusinessType.INSERT)
    public R<GzRecycleAppointmentVO> submit(@Valid @RequestBody GzRecycleAppointmentSubmitBo bo) {
        Long userId = LoginHelper.getUserId();
        if (userId == null) {
            return R.fail(401, "未登录");
        }
        log.info("[recycle-mp] submit userId={} storeId={} lines={} images={}",
            userId, bo.getStoreId(),
            bo.getProducts() == null ? 0 : bo.getProducts().size(),
            bo.getSubmitImageIds() == null ? 0 : bo.getSubmitImageIds().size());
        return R.ok(appointmentService.submit(bo, userId));
    }

    /**
     * 我的回收记录列表（按提交时间倒序，doc/12 §MP-RECYCLE-LIST）。
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
     * 回收预约详情（仅本人）。
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
     * /estimate 请求体（仅含 products，复用 SubmitBo.ProductLine 校验）。
     */
    @lombok.Data
    public static class EstimateRequest implements java.io.Serializable {

        @java.io.Serial
        private static final long serialVersionUID = 1L;

        /** 物品清单（品类 + 数量） */
        @jakarta.validation.constraints.NotEmpty(message = "请至少填写一项回收物品")
        @jakarta.validation.Valid
        private List<GzRecycleAppointmentSubmitBo.ProductLine> products;
    }
}
