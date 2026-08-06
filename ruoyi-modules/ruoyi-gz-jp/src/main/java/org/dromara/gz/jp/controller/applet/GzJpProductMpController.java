package org.dromara.gz.jp.controller.applet;

import cn.dev33.satoken.annotation.SaIgnore;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.R;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.gz.jp.domain.vo.GzJpProductMpVO;
import org.dromara.gz.jp.service.IGzJpProductService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * GZ-JP-103 拼团商品（mp 端，FLOW:F-JP-02.step1 进场挑商品 / 看商品详情）。
 *
 * <p>路径 {@code /app/gz/jp/product}（mp 前缀 {@code /app/} 与 admin {@code /system/} 区分），
 * 与 {@link GzJpEventMpController} 同一批 C 端只读接口。</p>
 *
 * <p><b>匿名可读</b>（{@link SaIgnore}）：浏览商品不需要登录态，与场列表一致；
 * 加购 / 下单（GZ-JP-201 起）才要求登录 —— 「先逛后登录」是本项目 mp 的既定口径
 * （无登录墙，见 CLAUDE.md 的 mp 隐私合规整改）。</p>
 *
 * <p><b>可见性（AC）</b>：只下发「商品 on_shelf <b>且</b> 所属场可浏览（open / closed）」的商品。
 * 已下架商品、未开场（draft）的场里的商品，一律查不到。</p>
 *
 * <p><b>已结束的场照常出商品</b>（UI:mp.event_detail「场已结束时商品仍可浏览，加购入口置灰」），
 * 每条带 {@code eventBookable=false} —— 前端据此置灰加购并提示「本场已结束」，
 * 别拿「查得到」当可以下单。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-JP-103)
 */
@Slf4j
@SaIgnore
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/app/gz/jp/product")
public class GzJpProductMpController {

    private final IGzJpProductService productService;

    /**
     * 场内商品分页（UI:mp.event_detail 两列网格，上拉加载）。
     *
     * <pre>
     * GET /app/gz/jp/product/list?eventId=1&amp;pageNum=1&amp;pageSize=10
     *
     * 200 OK
     * {
     *   "code": 200,
     *   "rows": [
     *     { "id":"1","productNo":"JPP-20260807-000001","eventId":"1",
     *       "eventNo":"EVT-20260807-000001","eventName":"8月上旬快闪场",
     *       "name":"柯南 吧唧 一番赏 A赏","mainImageUrl":"https://...","galleryImageUrls":[],
     *       "priceCent":12800,"deliveryDateText":"8月下旬","noticeText":"...","status":"on_shelf",
     *       "eventBookable":true }
     *   ],
     *   "total": 1
     * }
     * </pre>
     *
     * <p>未开场（draft）/ 场不存在时返回<b>空页</b>（rows=[]，total=0）而非报错 —— mp 进场前已从
     * {@code GET /app/gz/jp/event/{id}} 拿到明确信号。场已结束时<b>照常返回商品</b>，
     * 只是每条 {@code eventBookable=false}。</p>
     *
     * @param eventId   场主键（必传）
     * @param pageQuery 分页（pageNum / pageSize，ruoyi 自动绑定）
     * @return 商品列表（<b>图集恒为空数组</b>，需要图集打详情接口）
     */
    @GetMapping("/list")
    public TableDataInfo<GzJpProductMpVO> list(
        // required=false 是刻意的：Spring 的 required=true 会先抛 MissingServletRequestParameterException，
        // 落进 GlobalExceptionHandler 的兜底分支变成「发生未知异常，请联系管理员」+ ERROR 堆栈；
        // 交给 @NotNull 走 ConstraintViolationException 才能把「请选择场」原样回给调用方
        @NotNull(message = "请选择场") @RequestParam(value = "eventId", required = false) Long eventId,
        PageQuery pageQuery) {
        return productService.selectMpPage(eventId, pageQuery);
    }

    /**
     * 商品详情（UI:mp.product_detail）—— 主图 + 图集 + 价格 + 预计到货 + ★ 注意事项。
     *
     * @param id 商品主键
     * @return R&lt;detail&gt;；商品不存在 / 已下架 / 所属场未开场 → R.fail（mp 按 code != 200 走失效提示）。
     *         场已结束不是失败，照常 R.ok 并带 {@code eventBookable=false}
     */
    @GetMapping("/{id}")
    public R<GzJpProductMpVO> detail(@NotNull @PathVariable Long id) {
        GzJpProductMpVO vo = productService.selectMpDetail(id);
        if (vo == null) {
            return R.fail("该商品已下架");
        }
        return R.ok(vo);
    }
}
