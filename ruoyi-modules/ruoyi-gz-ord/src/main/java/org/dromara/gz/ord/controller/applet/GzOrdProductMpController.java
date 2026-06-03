package org.dromara.gz.ord.controller.applet;

import cn.dev33.satoken.annotation.SaIgnore;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.R;
import org.dromara.gz.ord.domain.dto.applet.OrdProductListReq;
import org.dromara.gz.ord.domain.dto.applet.ValidatePurchaseReq;
import org.dromara.gz.ord.domain.vo.applet.OrdProductDetailVO;
import org.dromara.gz.ord.domain.vo.applet.OrdProductMpListVO;
import org.dromara.gz.ord.domain.vo.applet.ValidatePurchaseVO;
import org.dromara.gz.ord.exception.GzOrdErrorCode;
import org.dromara.gz.ord.service.IGzOrdProductService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * GZ-ORD-102 / 103 mp 端预购商品 Controller（C 端浏览）。
 *
 * <p>路径前缀 {@code /app/gz/ord/product}（mp {@code /app/} 与 admin {@code /system/gz/ord/product} 域隔离）。</p>
 *
 * <p><b>匿名可读</b>（{@link SaIgnore}）：doc/10 §7 触发 = 「首页推荐卡 → 预购列表」，浏览不要求登录态
 * （同 gz-news mp 资讯）。下单 / 校验（ORD-103/104）才需登录，届时在对应端点上单独挂鉴权。不挂
 * {@code @SaCheckPermission} —— C 端浏览无需店员后台权限。</p>
 *
 * <p>端点（GZ-ORD-102 落地 list / ip-tags；ORD-103 在本类<b>追加</b> getDetail / validatePurchase）：</p>
 * <ul>
 *   <li>{@code GET /list} — 预购列表（status=on_shelf + 未截止 + 排序 + IP 筛选，AC1/AC2）</li>
 *   <li>{@code GET /ip-tags} — 在售商品去重 IP 标签（AC3，前端筛选 chip 数据源）</li>
 *   <li>{@code GET /{id}} — 商品详情（GZ-ORD-103 追加）</li>
 *   <li>{@code POST /validate-purchase} — 下单前校验（GZ-ORD-103 追加，productId 在 body）</li>
 * </ul>
 *
 * @author kevin-coder (sensenran-guzi · GZ-ORD-102)
 */
@Slf4j
@SaIgnore
@RestController
@RequiredArgsConstructor
@RequestMapping("/app/gz/ord/product")
public class GzOrdProductMpController {

    private final IGzOrdProductService productService;

    /**
     * 预购列表（GZ-ORD-102 AC1/AC2）。
     *
     * <pre>
     * GET /app/gz/ord/product/list?pageNum=1&pageSize=20&sortBy=deadline_asc&ipTags=CHIIKAWA,海贼王
     *   sortBy 枚举：arrival_asc / hot / deadline_asc（默认 deadline_asc，非法兜底）
     *   ipTags  ：逗号分隔，可空；最多前 10 个（超出截断 + warn）
     *
     * 200 OK
     * {
     *   "code": 200,
     *   "data": {
     *     "rows": [
     *       { "id":"12","name":"...","mainImageUrl":"https://...","startPriceCent":12900,
     *         "ipTag":"CHIIKAWA","deadlineTime":"2026-08-20T23:59:59","deliveryText":"2026-09-15",
     *         "salesCount":42 }
     *     ],
     *     "total": 8,
     *     "serverNow": "2026-06-03T15:30:00"
     *   }
     * }
     * </pre>
     *
     * @param req 分页 + 排序 + ipTags（ruoyi 自动绑定 query）
     * @return R&lt;{ rows, total, serverNow }&gt;
     */
    @GetMapping("/list")
    public R<OrdProductMpListVO> list(OrdProductListReq req) {
        return R.ok(productService.listForMp(req));
    }

    /**
     * 在售商品去重 IP 标签（GZ-ORD-102 AC3，前端筛选 chip 数据源，不前端硬编码）。
     *
     * <pre>
     * GET /app/gz/ord/product/ip-tags
     * 200 OK { "code": 200, "data": ["CHIIKAWA", "海贼王", "JOJO"] }
     * </pre>
     *
     * @return R&lt;在售 IP 标签列表&gt;（无在售商品 → 空数组）
     */
    @GetMapping("/ip-tags")
    public R<List<String>> ipTags() {
        return R.ok(productService.listOnSaleIpTags());
    }

    /**
     * 商品详情（GZ-ORD-103 AC1）。匿名可读（浏览不要求登录）。
     *
     * <pre>
     * GET /app/gz/ord/product/12
     * 200 OK
     * {
     *   "code": 200,
     *   "data": {
     *     "id":"12","name":"...","mainImageUrl":"https://...","galleryImageUrls":["https://...","..."],
     *     "descriptionHtml":"&lt;p&gt;...&lt;/p&gt;","ipTag":"CHIIKAWA",
     *     "deadlineTime":"2026-08-20T23:59:59","deliveryText":"2026-09-15",
     *     "status":"on_shelf","salesCount":42,
     *     "skus":[ {"id":"100","specName":"标准款","priceCent":12900,"stockRemain":50,"enabled":1} ],
     *     "serverNow":"2026-06-03T15:30:00"
     *   }
     * }
     * </pre>
     *
     * <p>商品不存在（含软删）→ {@code R.fail(PRODUCT_NOT_FOUND)}，前端 toast 兜底 + 返列表。</p>
     *
     * @param id 商品主键
     * @return R&lt;OrdProductDetailVO&gt;
     */
    @GetMapping("/{id}")
    public R<OrdProductDetailVO> getDetail(@PathVariable Long id) {
        OrdProductDetailVO vo = productService.getDetailForMp(id);
        if (vo == null) {
            return R.fail(GzOrdErrorCode.PRODUCT_NOT_FOUND, GzOrdErrorCode.PRODUCT_NOT_FOUND_MSG);
        }
        return R.ok(vo);
    }

    /**
     * 下单前校验（GZ-ORD-103 AC3）。匿名可读 —— 登录引导由 mp 端 usePurchaseGuard 在调用前做
     * （doc/10 §1.E1），本端点只做商品 / SKU / 库存即时校验（即时 UX 反馈，决策 D1）。
     *
     * <pre>
     * POST /app/gz/ord/product/validate-purchase
     * body { "productId":"12", "skuId":"100", "quantity":2 }
     * 200 OK { "code":200, "data": { "ok":true, "errCode":"OK" } }
     * 失败示例 data：{ "ok":false, "errCode":"PRODUCT_OFF" } / "SKU_OUT_OF_STOCK" / "PRODUCT_NOT_FOUND"
     * </pre>
     *
     * <p>校验失败<b>不抛异常</b>（HTTP 200 + 业务 code=200 + data.errCode）—— 避免 mp http 拦截器弹通用
     * toast，让前端按 errCode 差异化处理（PRODUCT_OFF 拦截重选 / SKU_OUT_OF_STOCK 提示刷新）。</p>
     *
     * @param req productId + skuId + quantity
     * @return R&lt;ValidatePurchaseVO&gt;（始终 R.ok 包裹，errCode 在 data 内）
     */
    @PostMapping("/validate-purchase")
    public R<ValidatePurchaseVO> validatePurchase(@Valid @RequestBody ValidatePurchaseReq req) {
        return R.ok(productService.validatePurchase(req));
    }
}
