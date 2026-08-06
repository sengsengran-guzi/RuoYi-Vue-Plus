package org.dromara.gz.jp.controller.applet;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.R;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.gz.jp.domain.bo.GzJpCartAddBo;
import org.dromara.gz.jp.domain.bo.GzJpCartQtyBo;
import org.dromara.gz.jp.domain.vo.GzJpCartVO;
import org.dromara.gz.jp.service.IGzJpCartService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * GZ-JP-104 拼团购物车（mp 端，FLOW:F-JP-02.step2 加入购物车 / 改数量 / 删除）。
 *
 * <p>路径 {@code /app/gz/jp/cart}（mp 前缀 {@code /app/} 与 admin {@code /system/} 区分）。
 * admin 侧<b>没有</b>对应端点 —— 购物车是纯 C 端数据，后台不管理
 * （field-ssot 的 {@code gz_jp_cart_item} 段标注「无 menu」）。</p>
 *
 * <p><b>★ 全部端点需要登录态</b>（本类<b>刻意没有</b> {@code @SaIgnore}，与同模块的
 * {@link GzJpEventMpController} / {@link GzJpProductMpController} 只读匿名接口相反）：
 * 购物车是用户私有数据，未登录 → sa-token 全局拦截 401。
 * 「先逛后登录」的分界线就在这里 —— 逛场 / 看商品不用登录，加购起要登录。</p>
 *
 * <p><b>userId 一律取登录态</b>（{@code LoginHelper.getUserId()} = {@code gz_user.id}），
 * 任何入参里都没有 userId 字段 —— 让调用方传 userId 等于把别人的车开放给任何人读写。</p>
 *
 * <p>端点：</p>
 * <ul>
 *   <li>{@code GET    /list}      — 购物车（按场分组 + 实时标失效项）</li>
 *   <li>{@code POST   /}          — 加入购物车（同商品已存在则数量累加）</li>
 *   <li>{@code PUT    /}          — 改数量（绝对赋值）</li>
 *   <li>{@code DELETE /{ids}}     — 删除（支持批量，逗号分隔）</li>
 * </ul>
 *
 * @author kevin-coder (sensenran-guzi · GZ-JP-104)
 */
@Slf4j
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/app/gz/jp/cart")
public class GzJpCartMpController {

    private final IGzJpCartService cartService;

    /**
     * 购物车列表（UI:mp.cart，<b>按场分组</b>）。
     *
     * <pre>
     * GET /app/gz/jp/cart/list
     *
     * 200 OK
     * {
     *   "code": 200,
     *   "data": {
     *     "groups": [
     *       { "eventId":"1","eventNo":"EVT-20260807-000001","eventName":"8月上旬快闪场",
     *         "eventStatus":"open","eventBookable":true,
     *         "items": [
     *           { "id":"2","productId":"1","productNo":"JPP-20260807-000001",
     *             "name":"柯南 吧唧 一番赏 A赏","mainImageUrl":"https://...",
     *             "priceCent":12800,"qty":2,"amountCent":25600,"eventId":"1",
     *             "invalid":false,"invalidReason":null,"invalidText":null }
     *         ] },
     *       { "eventId":"5","eventName":"7月旧场","eventStatus":"closed","eventBookable":false,
     *         "items":[ { ...,"invalid":true,"invalidReason":"event_closed","invalidText":"本场已结束" } ] }
     *     ],
     *     "itemCount": 2, "invalidCount": 1, "validQty": 2, "validAmountCent": 25600
     *   }
     * }
     * </pre>
     *
     * <p><b>★ 失效项（{@code invalid=true}）不可勾选、不计入 {@code validAmountCent}</b>
     * —— 前端据此置灰并标「已失效」，别自己拿场的 endTime 跟本地时钟比。</p>
     *
     * <p>★ 合计只有商品一行，<b>没有运费</b>（REQ-ORDER-004 全包邮），VO 里也没有运费字段。</p>
     *
     * @return 整车 VO；空车时 groups 为空数组、各计数为 0
     */
    @GetMapping("/list")
    public R<GzJpCartVO> list() {
        Long userId = LoginHelper.getUserId();
        if (userId == null) {
            return R.fail(401, "未登录");
        }
        return R.ok(cartService.selectCart(userId));
    }

    /**
     * 加入购物车（FLOW:F-JP-02.step2）。
     *
     * <pre>
     * POST /app/gz/jp/cart      Body: { "productId": "1", "qty": 2 }
     * 200 OK { "code":200, "msg":"操作成功", "data": 3 }   ← data = 累加后该商品在车里的数量
     *
     * 业务错误（R.fail，mp 按 msg 提示）：
     *   「该商品已下架」            商品不存在 / 已软删 / off_shelf
     *   「本场已结束，无法加入购物车」 所属场不可下单（关场 / 到 end_time）
     *   「该商品数量已达上限（99 件）」
     *   「购物车最多放 50 款商品，请先结算或删除部分商品」
     * </pre>
     *
     * <p><b>★ 同一商品重复加购 = 数量累加，不产生第二行</b>（AC 第 1 条）。</p>
     *
     * @param bo 商品 + 本次加入数量（缺省 1）
     * @return 累加后该商品在车里的数量
     */
    @PostMapping
    public R<Integer> add(@Valid @RequestBody GzJpCartAddBo bo) {
        Long userId = LoginHelper.getUserId();
        if (userId == null) {
            return R.fail(401, "未登录");
        }
        log.info("[gz-jp-cart-mp] add userId={} productId={} qty={}", userId, bo.getProductId(), bo.getQty());
        return R.ok(cartService.addItem(userId, bo.getProductId(), bo.getQty()));
    }

    /**
     * 修改数量（stepper，<b>绝对赋值</b>不是增量）。
     *
     * <pre>
     * PUT /app/gz/jp/cart      Body: { "id": "2", "qty": 5 }
     * 200 OK { "code":200, "msg":"操作成功" }
     *
     * 「购物车项不存在」= 该项不存在<b>或</b>不属于当前用户（刻意不区分，不泄漏别人车里有什么）
     * </pre>
     *
     * <p>数量减到 0 请走 {@code DELETE}，本接口最小值为 1。</p>
     *
     * @param bo 购物车项 id + 目标数量
     */
    @PutMapping
    public R<Void> updateQty(@Valid @RequestBody GzJpCartQtyBo bo) {
        Long userId = LoginHelper.getUserId();
        if (userId == null) {
            return R.fail(401, "未登录");
        }
        cartService.updateQty(userId, bo.getId(), bo.getQty());
        return R.ok();
    }

    /**
     * 删除购物车项（支持批量，「清空失效商品」由前端收集失效项 id 一次调用即可）。
     *
     * <pre>
     * DELETE /app/gz/jp/cart/2        单个
     * DELETE /app/gz/jp/cart/2,3,4    批量（逗号分隔，ruoyi 惯例）
     * 200 OK { "code":200, "data": 3 }   ← data = 实际删除行数
     * </pre>
     *
     * <p>只删属于本人的行；混入别人的 id 时那些行被静默忽略（删除数会小于传入数）。
     * <b>物理删</b> —— 购物车表软删会撞 {@code uk_user_product}，删了再加同款商品会 409。</p>
     *
     * @param ids 购物车项主键（非购物车项则忽略）
     * @return 实际删除行数
     */
    @DeleteMapping("/{ids}")
    public R<Integer> remove(@NotEmpty(message = "请选择要删除的商品") @PathVariable Long[] ids) {
        Long userId = LoginHelper.getUserId();
        if (userId == null) {
            return R.fail(401, "未登录");
        }
        return R.ok(cartService.deleteItems(userId, List.of(ids)));
    }
}
