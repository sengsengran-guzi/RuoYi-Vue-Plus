package org.dromara.gz.user.controller.applet;

import cn.dev33.satoken.annotation.SaCheckLogin;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.R;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.gz.user.domain.vo.GzUnifiedOrderVo;
import org.dromara.gz.user.domain.vo.UnifiedOrderDetailVo;
import org.dromara.gz.user.service.IGzLogisticsSignService;
import org.dromara.gz.user.service.IGzUnifiedOrderService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * GZ-USER-101 mp 端「我的订单」统一聚合 Controller —— 跨预购 + 扭蛋两类业务订单。
 *
 * <p>路径前缀 {@code /app/gz/user/orders}（mp {@code /app/} 域；项目 mp 统一前缀，ticket 卡里
 * {@code /api/user/orders} 为口径简写）。<b>仅登录态</b>（{@link SaCheckLogin}）—— 这是 <b>C 端用户</b>
 * 看自己订单的接口，按 {@code user_id} 归属隔离（service 内取 sa-token 当前 user，AC7）。</p>
 *
 * <p><b>关于 ticket AC1 的 {@code @SaCheckPermission("gz:user:orders:list")}</b>：本仓库 mp 路由上的
 * {@code @SaCheckPermission} 是 <b>店员专用门</b>（ADR-0004：仅绑定 owner/staff 的 app_user 会话带
 * {@code gz:*} 权限，纯顾客 app_user token 不带 → sa-token 403）。若给 C 端「我的订单」挂该注解，
 * 所有普通顾客都会 403，功能不可用。D10 README §1 明确这是 C 端用户功能，且全仓 C 端「我的 X」
 * 路由（ord {@code /list} / gacha collection {@code /my} / bean {@code /my}）均只用 {@code @SaCheckLogin}
 * + user_id 归属。故此处按既有 C 端鉴权模型用 {@code @SaCheckLogin}，AC1 偏差见 reports/GZ-USER-101.md
 * §AC1 偏差说明（待 product 在 doc/11 §8.1 / ticket 回填修订源头）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-USER-101)
 */
@Slf4j
@SaCheckLogin
@RestController
@RequiredArgsConstructor
@RequestMapping("/app/gz/user/orders")
public class GzUserOrderMpController {

    private final IGzUnifiedOrderService unifiedOrderService;
    private final IGzLogisticsSignService logisticsSignService;

    /**
     * 我的订单统一列表（AC1~AC10）。
     *
     * <pre>
     * GET /app/gz/user/orders?bizType=all&pageNum=1&pageSize=10
     *   bizType: all（默认）/ preorder / gacha
     * 200 OK
     * {
     *   "code": 200, "msg": "查询成功", "total": 5,
     *   "rows": [ {
     *     "orderNo": "GACHA-20260618-000002", "businessType": "gacha", "userId": "1001",
     *     "productSnapshotJson": "{\"name\":\"初音 应援款\",\"cover\":\"3001\",\"spec\":\"SSR\",\"machine\":\"初音盲盒机\",\"rarity\":\"SSR\"}",
     *     "totalAmountCent": 3900, "businessStatus": "pending_ship", "logisticsStatus": "in_japan",
     *     "cnCarrierCode": null, "cnTrackingNo": null, "addressSnapshotJson": null,
     *     "paidTime": "2026-06-18T20:31:05", "deliveredTime": null, "createdAt": "2026-06-18T20:31:05"
     *   }, {
     *     "orderNo": "PREORD-20260617-000001", "businessType": "preorder", "userId": "1001",
     *     "productSnapshotJson": "{\"productId\":\"...\",\"name\":\"...\",\"mainImageId\":\"...\",...}",
     *     "totalAmountCent": 19800, "businessStatus": "paid", "logisticsStatus": "in_japan",
     *     "cnCarrierCode": null, "cnTrackingNo": null, "addressSnapshotJson": "{...}",
     *     "paidTime": "2026-06-17T10:00:00", "deliveredTime": null, "createdAt": "2026-06-17T09:59:30"
     *   } ]
     * }
     * </pre>
     *
     * <p>返标准 ruoyi 分页结构 {@link TableDataInfo}（rows + total + code + msg，mp http 拦截器识别）。</p>
     *
     * @param bizType  all / preorder / gacha（缺省 / 非法 → all）
     * @param pageNum  页码（缺省 1）
     * @param pageSize 每页条数（缺省 10，上限 50，超限截断）
     * @return 统一订单分页（时间倒序）
     */
    @GetMapping
    public TableDataInfo<GzUnifiedOrderVo> list(
        @RequestParam(value = "bizType", required = false, defaultValue = "all") String bizType,
        @RequestParam(value = "pageNum", required = false, defaultValue = "1") Integer pageNum,
        @RequestParam(value = "pageSize", required = false, defaultValue = "10") Integer pageSize) {
        Long userId = LoginHelper.getUserId();
        if (userId == null) {
            return TableDataInfo.build();
        }
        return unifiedOrderService.listByUser(userId, bizType, pageNum, pageSize);
    }

    /**
     * 我的订单统一详情（GZ-USER-102 AC1~AC5）。
     *
     * <pre>
     * GET /app/gz/user/orders/GACHA-20260618-000002
     * 200 OK
     * {
     *   "code": 200,
     *   "data": {
     *     "orderNo": "GACHA-20260618-000002", "businessType": "gacha", "userId": "1001",
     *     "productSnapshotJson": "{\"name\":\"初音 应援款\",\"cover\":\"3001\",\"spec\":\"SSR\",\"machine\":\"初音盲盒机\",\"rarity\":\"SSR\"}",
     *     "totalAmountCent": 3900, "businessStatus": "pending_ship", "logisticsStatus": "in_japan",
     *     "cnCarrierCode": null, "cnTrackingNo": null, "addressSnapshotJson": null,
     *     "paidTime": "2026-06-18T20:31:05", "deliveredTime": null, "createdAt": "2026-06-18T20:31:05",
     *     "gachaSnapshot": { "prizeName":"初音 应援款","prizeImageId":"3001","rarity":"SSR","machineName":"初音盲盒机","paidTime":"2026-06-18T20:31:05" },
     *     "preorderSnapshot": null
     *   }
     * }
     * 非本人 / 不存在 / 非法 orderNo：R.fail(403, "无权查看该订单")
     * </pre>
     *
     * <p>归属强约束（AC1）：service 内 {@code where order_no} 命中后比对 {@code user_id = 当前登录 id}，
     * 不一致 / 查无 → 返 null → 此处统一 {@code R.fail(403)}（前端 hide 不算防御）。
     * 不区分「不存在」与「越权」以免泄露订单存在性。</p>
     *
     * @param orderNo 订单业务码（PREORD-... / GACHA-...）
     * @return R&lt;UnifiedOrderDetailVo&gt;
     */
    @GetMapping("/{orderNo}")
    public R<UnifiedOrderDetailVo> detail(@PathVariable String orderNo) {
        Long userId = LoginHelper.getUserId();
        if (userId == null) {
            return R.fail(401, "未登录");
        }
        UnifiedOrderDetailVo vo = unifiedOrderService.getDetail(orderNo, userId);
        if (vo == null) {
            return R.fail(403, "无权查看该订单");
        }
        return R.ok(vo);
    }

    /**
     * 用户主动确认收货（GZ-USER-104 AC3）。
     *
     * <pre>
     * POST /app/gz/user/orders/PREORD-20260617-000001/confirm-receive
     * 200 OK { "code": 200, "msg": "签收成功" }
     * 越权 / 不存在 / 非法 orderNo：R.fail("无权操作该订单")
     * 状态非法（非 in_china_dispatching，含已 delivered）：R.fail("订单状态不允许此操作")
     * </pre>
     *
     * <p>归属 + 状态校验在 service 层（{@code where user_id = 当前登录 id} 防越权；仅 in_china_dispatching
     * 可签；行级条件 UPDATE 幂等不可回退，AC4）。签收同事务写 {@code gz_logistics_audit}（user_confirmed）。
     * 业务异常由 service 抛 {@code ServiceException} → ruoyi 全局兜底转 R.fail（此处不额外 try-catch，
     * CLAUDE.md §6 #7 不吞异常）。</p>
     *
     * @param orderNo 订单业务码（PREORD-... / GACHA-...）
     * @return R&lt;Void&gt;
     */
    @PostMapping("/{orderNo}/confirm-receive")
    public R<Void> confirmReceive(@PathVariable String orderNo) {
        Long userId = LoginHelper.getUserId();
        if (userId == null) {
            return R.fail(401, "未登录");
        }
        logisticsSignService.confirmReceive(orderNo, userId);
        return R.ok("签收成功");
    }
}
