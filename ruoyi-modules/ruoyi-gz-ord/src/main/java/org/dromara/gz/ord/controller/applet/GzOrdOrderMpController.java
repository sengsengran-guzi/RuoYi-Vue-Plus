package org.dromara.gz.ord.controller.applet;

import cn.dev33.satoken.annotation.SaCheckLogin;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.R;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.gz.ord.domain.dto.applet.SubmitOrderReq;
import org.dromara.gz.ord.domain.vo.applet.OrdOrderDetailVO;
import org.dromara.gz.ord.domain.vo.applet.OrdOrderListItemVO;
import org.dromara.gz.ord.domain.vo.applet.SubmitOrderVO;
import org.dromara.gz.ord.exception.GzOrdErrorCode;
import org.dromara.gz.ord.service.IGzOrdOrderService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * GZ-ORD-104 mp 端预购订单 Controller（下单 / 取消 / 详情）。
 *
 * <p>路径前缀 {@code /app/gz/ord/order}（mp {@code /app/} 域）。<b>需登录态</b>（{@link SaCheckLogin}）——
 * 下单 / 取消 / 看订单都要 user_id 归属（与 ORD-102/103 匿名浏览不同）。</p>
 *
 * <p>端点：</p>
 * <ul>
 *   <li>{@code POST /submit}      — 提交订单（跨域事务：扣库存 + INSERT order + 调 PAY 建单，AC2）</li>
 *   <li>{@code POST /cancel/{id}} — 取消订单（仅 created，回滚库存，AC6）</li>
 *   <li>{@code GET  /{id}}        — 订单详情（供 pay-result 轮询 + 详情展示，AC5；ORD-105 扩 list）</li>
 * </ul>
 *
 * <p>错误返回口径：submit/cancel 的业务异常走 {@code ServiceException}（GzOrdErrorCode），
 * 统一被 ruoyi 全局异常处理器转 {@code R.fail(code, msg)}（HTTP 200 + 业务 code）—— mp 按 code 分流 UX
 * （PRODUCT_OFF/ADDRESS_INVALID/SKU_OUT_OF_STOCK/ORDER_NOT_CANCELLABLE）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-ORD-104)
 */
@Slf4j
@SaCheckLogin
@RestController
@RequiredArgsConstructor
@RequestMapping("/app/gz/ord/order")
public class GzOrdOrderMpController {

    private final IGzOrdOrderService ordOrderService;

    /**
     * 提交预购订单（AC2，跨域事务）。
     *
     * <pre>
     * POST /app/gz/ord/order/submit
     * body { "productId":"12", "skuId":"100", "qty":2, "addressId":"5", "userNote":"尽快发货" }
     * 200 OK
     * {
     *   "code": 200,
     *   "data": {
     *     "ordOrderId": "8001",
     *     "orderNo": "PREORD-20260603-000001",
     *     "payParams": { "timeStamp":"...","nonceStr":"...","packageVal":"prepay_id=...","signType":"RSA","paySign":"...","outTradeNo":"PREORD-..." }
     *   }
     * }
     * 失败：R.fail(7009 商品已截止) / R.fail(7008 地址无效) / R.fail(7001 库存不足)
     * </pre>
     *
     * @param req productId + skuId + qty + addressId + userNote?
     * @return R&lt;SubmitOrderVO&gt;（ordOrderId + orderNo + 微信 5 参签名）
     */
    @PostMapping("/submit")
    public R<SubmitOrderVO> submit(@Valid @RequestBody SubmitOrderReq req) {
        Long userId = LoginHelper.getUserId();
        if (userId == null) {
            return R.fail(401, "未登录");
        }
        return R.ok(ordOrderService.submit(req, userId));
    }

    /**
     * 取消预购订单（AC6，仅 created，回滚库存）。
     *
     * <pre>
     * POST /app/gz/ord/order/cancel/8001
     * 200 OK { "code": 200 }
     * 已 paid：R.fail(7010 订单当前状态不可取消)
     * </pre>
     *
     * @param id 订单 id
     * @return R&lt;Void&gt;
     */
    @PostMapping("/cancel/{id}")
    public R<Void> cancel(@PathVariable Long id) {
        Long userId = LoginHelper.getUserId();
        if (userId == null) {
            return R.fail(401, "未登录");
        }
        ordOrderService.cancel(id, userId);
        return R.ok();
    }

    /**
     * 我的订单列表（GZ-ORD-105 AC1）。
     *
     * <pre>
     * GET /app/gz/ord/order/list?pageNum=1&pageSize=10&chipStatus=all
     * chipStatus: all / to_pay / to_ship / shipping / done / cancelled / refunded
     *   （chip → business_status 过滤严格按 doc/11 §8.2；非法 / 缺省 → all 不过滤）
     * 200 OK { "code":200, "rows":[ { "id":"8001","orderNo":"PREORD-...","productName":"...",
     *          "productImageUrl":"https://...","specName":"标准款","qty":2,"totalAmountCent":19800,
     *          "businessStatus":"paid","logisticsStatus":"in_japan","chipStatus":"to_ship",
     *          "chipLabel":"待发货","createTime":"..." } ], "total": 12 }
     * </pre>
     *
     * <p>分页响应直返 {@link TableDataInfo}（rows + total，mp http 拦截器识别该结构）。
     * sa-token 取当前 user_id（同租户 1001），只返本人订单。</p>
     *
     * @param chipStatus 用户视角统一 chip（默认 all）
     * @param pageQuery  pageNum / pageSize
     * @return 列表卡分页
     */
    @GetMapping("/list")
    public TableDataInfo<OrdOrderListItemVO> list(
        @RequestParam(value = "chipStatus", required = false, defaultValue = "all") String chipStatus,
        PageQuery pageQuery) {
        Long userId = LoginHelper.getUserId();
        if (userId == null) {
            return TableDataInfo.build();
        }
        return ordOrderService.pageForMp(chipStatus, userId, pageQuery);
    }

    /**
     * 订单详情（AC5，供 pay-result 轮询 + 详情展示）。
     *
     * <pre>
     * GET /app/gz/ord/order/8001
     * 200 OK { "code":200, "data": { "id":"8001","orderNo":"PREORD-...","businessStatus":"paid",
     *          "logisticsStatus":"in_japan","product":{...},"sku":{...},"address":{...},... } }
     * 不存在 / 非本人：R.fail(7011 订单不存在)
     * </pre>
     *
     * @param id 订单 id
     * @return R&lt;OrdOrderDetailVO&gt;
     */
    @GetMapping("/{id}")
    public R<OrdOrderDetailVO> getDetail(@PathVariable Long id) {
        Long userId = LoginHelper.getUserId();
        if (userId == null) {
            return R.fail(401, "未登录");
        }
        OrdOrderDetailVO vo = ordOrderService.getDetail(id, userId);
        if (vo == null) {
            return R.fail(GzOrdErrorCode.ORDER_NOT_FOUND, GzOrdErrorCode.ORDER_NOT_FOUND_MSG);
        }
        return R.ok(vo);
    }
}
