package org.dromara.gz.jp.controller.applet;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.R;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.gz.jp.domain.bo.GzJpOrderSubmitBo;
import org.dromara.gz.jp.domain.vo.GzJpOrderDetailVO;
import org.dromara.gz.jp.domain.vo.GzJpOrderListItemVO;
import org.dromara.gz.jp.domain.vo.GzJpOrderSubmitVO;
import org.dromara.gz.jp.exception.GzJpOrderErrorCode;
import org.dromara.gz.jp.service.IGzJpOrderService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * GZ-JP-105 拼团订单（mp 端，FLOW:F-JP-02.step4 提交订单 / step5 支付）。
 *
 * <p>路径 {@code /app/gz/jp/order}（mp 前缀 {@code /app/} 与 admin {@code /system/} 区分）。
 * admin 侧订单管理是<b>另一套只读端点</b>，归 GZ-JP-109（菜单 14020 段），不复用本类。</p>
 *
 * <p><b>★ 全部端点需要登录态</b>（本类刻意没有 {@code @SaIgnore}）：订单是用户私有数据。
 * {@code userId} 一律取登录态，入参里没有 userId 字段。</p>
 *
 * <p><b>★ 没有「取消订单」端点</b>：一期甲方未要求，未支付单由 GZ-PAY 的 5 分钟超时关单收尾；
 * 已支付的钱走 GZ-JP-107 行级退款。{@code GzJpOrderMapper.markCancelled} 已备好，
 * 真要放开时补一个端点即可（service 层同时要补「仅 created 可取消」的判定）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-JP-105)
 */
@Slf4j
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/app/gz/jp/order")
public class GzJpOrderMpController {

    private final IGzJpOrderService orderService;

    /**
     * 提交订单（FLOW:F-JP-02.step4）—— 建单 + 落快照 + 拿微信支付 5 参，<b>一个事务</b>。
     *
     * <pre>
     * POST /app/gz/jp/order
     * Body: { "cartItemIds": ["12","13"], "addressId": "3",
     *         "userNote": "麻烦包严实点", "expectedAmountCent": 51200 }   // expectedAmountCent 可选
     *
     * 200 OK
     * {
     *   "code": 200,
     *   "data": {
     *     "orderId": "8", "orderNo": "JPO-20260807-000001",
     *     "totalAmountCent": 51200,          // ★ 后端重算的最终成交价，前端以此为准
     *     "itemCount": 2,
     *     "payParams": { "timeStamp":"...", "nonceStr":"...", "packageVal":"prepay_id=...",
     *                    "signType":"RSA", "paySign":"...", "outTradeNo":"JPO-20260807-000002" }
     *   }
     * }
     *
     * 业务错误（HTTP 200 + body code，前端按 code 分支、msg 直接 toast）：
     *   4101 请选择要结算的商品           购物车勾选项一个都不属于本人 / 已被清空
     *   4102 「XXX」已下架 / 所在的场已结束  ★ 前端应退回购物车并高亮，别停在确认页重试
     *   4103 收货地址无效，请重新选择
     *   4104 商品价格有变动，当前应付 XX 元  ★ 刷新确认页让客人重新确认（仅传了 expectedAmountCent 时可能出现）
     *   401  未登录 / 用户信息异常
     * </pre>
     *
     * <p><b>★ 一单可含多场商品</b>（购物车跨场共存，结算不拆单）。
     * <b>★ 金额只有商品合计一行，无运费</b>（REQ-ORDER-004 全包邮）。</p>
     *
     * @param bo 勾选的购物车项 + 地址 + 备注（+ 可选的确认页金额，仅比对用）
     * @return 订单号 + 后端重算金额 + 支付 5 参
     */
    @PostMapping
    public R<GzJpOrderSubmitVO> submit(@Valid @RequestBody GzJpOrderSubmitBo bo) {
        Long userId = LoginHelper.getUserId();
        if (userId == null) {
            return R.fail(401, "未登录");
        }
        log.info("[gz-jp-order-mp] submit userId={} cartItemIds={} addressId={}",
            userId, bo.getCartItemIds(), bo.getAddressId());
        return R.ok(orderService.submit(bo, userId));
    }

    /**
     * 我的订单分页（UI:mp.order_list），下单时间倒序。
     *
     * <pre>
     * GET /app/gz/jp/order/list?pageNum=1&pageSize=10&businessStatus=created
     *
     * 200 OK { "code":200, "rows":[ { "id":"8","orderNo":"JPO-...","totalAmountCent":51200,
     *          "businessStatus":"paid","businessStatusLabel":"已支付","itemCount":2,"totalQty":5,
     *          "thumbUrls":["https://...","https://..."],"createTime":"...","paidTime":"..." } ],
     *          "total":1 }
     * </pre>
     *
     * <p><b>★ 读 {@code res.rows} 不是 {@code res.data}</b>（分页返回 {@code TableDataInfo}）——
     * 这一类前端契约 bug 在 D16 真跑 E2E 时抓到过 6 处。</p>
     *
     * <p>tab 到状态的映射由前端定（如「待支付」= {@code businessStatus=created}；
     * 「进行中 / 已完成」需要看商品行的履约状态，GZ-JP-205 拿详情里的 items 自行归组）。</p>
     *
     * @param businessStatus 订单状态过滤（不传 = 全部）
     * @param pageQuery      分页参数
     * @return 分页结果（无单时 rows 为空数组）
     */
    @GetMapping("/list")
    public TableDataInfo<GzJpOrderListItemVO> list(@RequestParam(required = false) String businessStatus,
                                                   PageQuery pageQuery) {
        Long userId = LoginHelper.getUserId();
        if (userId == null) {
            return TableDataInfo.build();
        }
        return orderService.selectMyPage(userId, businessStatus, pageQuery);
    }

    /**
     * 订单详情（仅本人，UI:mp.order_detail；支付结果页也用它回读状态）。
     *
     * <pre>
     * GET /app/gz/jp/order/8
     *
     * 200 OK { "code":200, "data": {
     *   "id":"8","orderNo":"JPO-20260807-000001","totalAmountCent":51200,
     *   "businessStatus":"paid","businessStatusLabel":"已支付",
     *   "address":{ "recipient":"张三","mobile":"138...","province":"四川省", ... },
     *   "items":[ { "id":"21","name":"柯南 吧唧 A赏","mainImageUrl":"https://...",
     *               "qty":4,"unitPriceCent":12800,"amountCent":51200,
     *               "fulfillStatus":"purchasing","fulfillStatusLabel":"购买中",
     *               "trackingNo":null,"noticeText":"..." } ],
     *   "itemCount":1,"totalQty":4,"createTime":"...","paidTime":"..." } }
     * </pre>
     *
     * <p><b>★ 履约状态逐行看</b>（{@code items[].fulfillStatus}），订单级
     * {@code businessStatus} 只回答「钱怎么样了」。<b>★ 无运费行</b>。</p>
     *
     * <p>订单不存在 / 不是本人 → 同样返回 {@code 4105 订单不存在}（不区分，不泄漏别人有没有这单）。</p>
     *
     * @param id 订单主键
     * @return 详情
     */
    @GetMapping("/{id}")
    public R<GzJpOrderDetailVO> detail(@PathVariable Long id) {
        Long userId = LoginHelper.getUserId();
        if (userId == null) {
            return R.fail(401, "未登录");
        }
        GzJpOrderDetailVO vo = orderService.getDetail(id, userId);
        if (vo == null) {
            return R.fail(GzJpOrderErrorCode.ORDER_NOT_FOUND, GzJpOrderErrorCode.ORDER_NOT_FOUND_MSG);
        }
        return R.ok(vo);
    }
}
