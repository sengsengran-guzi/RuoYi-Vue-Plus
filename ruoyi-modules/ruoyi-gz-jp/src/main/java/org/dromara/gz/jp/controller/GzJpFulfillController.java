package org.dromara.gz.jp.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.R;
import org.dromara.common.idempotent.annotation.RepeatSubmit;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.common.web.core.BaseController;
import org.dromara.gz.jp.domain.bo.GzJpFulfillAdvanceBo;
import org.dromara.gz.jp.domain.bo.GzJpFulfillQueryBo;
import org.dromara.gz.jp.domain.bo.GzJpFulfillShipBo;
import org.dromara.gz.jp.domain.bo.GzJpMarkFailedBo;
import org.dromara.gz.jp.domain.vo.GzJpFulfillBatchResultVO;
import org.dromara.gz.jp.domain.vo.GzJpFulfillBoardItemVO;
import org.dromara.gz.jp.domain.vo.GzJpMarkFailedResultVO;
import org.dromara.gz.jp.service.IGzJpFulfillService;
import org.dromara.gz.jp.service.IGzJpRefundService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * GZ-JP-106 履约推进（admin 端，UI:admin.fulfill_board / FLOW:F-JP-03.step2/step3）。
 *
 * <p>路径前缀 {@code /system/gz/jp/fulfill}。客人侧看到的履约状态走 mp 订单详情
 * （{@code /app/gz/jp/order/{id}}，GZ-JP-105 已提供），本控制器<b>不下发给客人</b>。</p>
 *
 * <p><b>权限</b>（菜单与按钮 seed 归 GZ-JP-108，menu_id 14030 段，<b>本卡不占号</b>）：</p>
 * <ul>
 *   <li>{@code gz:jp:fulfill:list} — 看板列表</li>
 *   <li>{@code gz:jp:fulfill:advance} — 批量推进状态</li>
 *   <li>{@code gz:jp:fulfill:ship} — 批量发货（填运单号）</li>
 *   <li>{@code gz:jp:fulfill:markFailed} — ★ 批量标记购买失败并<b>发起真实退款</b>（GZ-JP-107，
 *       刻意与 advance 分开授权：推状态与动客人的钱是两件事）</li>
 * </ul>
 * <p>108 seed 菜单前，超管（admin）可直接调用；普通角色需等 108 授权。</p>
 *
 * <p><b>没有「回退」端点</b> —— 一期不做（AC 明列）。误推的补救办法是业务层面沟通，
 * 不是给店员一个能把已发货的单改回去的按钮。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-JP-106)
 */
@Slf4j
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/system/gz/jp/fulfill")
public class GzJpFulfillController extends BaseController {

    private final IGzJpFulfillService fulfillService;
    /** GZ-JP-107 行级退款（mark-failed 会真的花钱，实现在 jp 域独立退款服务里，只共用支付通道层） */
    private final IGzJpRefundService refundService;

    /**
     * 履约看板列表（按客人聚簇排序，前端断组渲染）。
     *
     * <pre>
     * GET /system/gz/jp/fulfill/list?pageNum=1&amp;pageSize=20
     *     &amp;keyword=小王&amp;fulfillStatus=purchasing&amp;fulfillStatus=customs
     *     &amp;eventId=1&amp;orderNo=JPO-20260807-000001&amp;trackingNo=SF123
     *     &amp;beginDate=2026-08-01&amp;endDate=2026-08-07
     * </pre>
     *
     * <p>★ 只返回<b>付过款</b>订单的行，这道闸在服务层写死不可关闭。</p>
     */
    @SaCheckPermission("gz:jp:fulfill:list")
    @GetMapping("/list")
    public TableDataInfo<GzJpFulfillBoardItemVO> list(GzJpFulfillQueryBo query, PageQuery pageQuery) {
        return fulfillService.selectBoardPage(query, pageQuery);
    }

    /**
     * 批量推进履约状态（FLOW:F-JP-03.step2）。
     *
     * <pre>
     * POST /system/gz/jp/fulfill/advance
     * { "itemIds": ["12","13"], "targetStatus": "jp_shipped" }
     * </pre>
     *
     * <p>★ 允许跳过中间态；拒绝倒退 / 终态；<b>拒绝 {@code targetStatus=delivered}</b>
     * （要填运单号，改用 {@code /ship}，返回 4109）。</p>
     *
     * @return 计数 + 被拒明细；一行都推不动时抛 4108
     */
    @SaCheckPermission("gz:jp:fulfill:advance")
    @Log(title = "拼团履约推进", businessType = BusinessType.UPDATE)
    @RepeatSubmit()
    @PostMapping("/advance")
    public R<GzJpFulfillBatchResultVO> advance(@Validated @RequestBody GzJpFulfillAdvanceBo bo) {
        return R.ok("操作成功", fulfillService.advance(bo, LoginHelper.getUserId()));
    }

    /**
     * 批量发货（FLOW:F-JP-03.step3）—— 一批行置「发货完毕」并共用一个运单号。
     *
     * <pre>
     * POST /system/gz/jp/fulfill/ship
     * { "itemIds": ["12","13"], "carrierCode": "sf", "trackingNo": "SF1234567890" }
     * </pre>
     *
     * <p>★ 快递公司与运单号<b>必填</b>（4109）；★ 勾选行必须同属一个客人（4110）。
     * <b>不建包裹表</b> —— 单号即包裹标识。</p>
     */
    @SaCheckPermission("gz:jp:fulfill:ship")
    @Log(title = "拼团履约发货", businessType = BusinessType.UPDATE)
    @RepeatSubmit()
    @PostMapping("/ship")
    public R<GzJpFulfillBatchResultVO> ship(@Validated @RequestBody GzJpFulfillShipBo bo) {
        return R.ok("发货成功", fulfillService.ship(bo, LoginHelper.getUserId()));
    }

    /**
     * 批量标记购买失败 → 按<b>行金额</b>发起微信部分退款（GZ-JP-107，FLOW:F-JP-04.step1/step2）。
     *
     * <pre>
     * POST /system/gz/jp/fulfill/mark-failed
     * { "itemIds": ["24","25"], "reason": "日方缺货" }
     * </pre>
     *
     * <p><b>⚠️ 这个接口会花钱</b>：每一行都按该行 {@code amount_cent} 向微信发起<b>真实退款</b>。
     * admin 侧必须二次确认（UI:admin.fulfill_board.mark_failed）。</p>
     *
     * <p><b>★ 与 {@code /advance targetStatus=purchase_failed} 的区别</b>：那个只改状态<b>不退钱</b>
     * （106 遗留能力）。要退钱必须走本端点。已经被 {@code /advance} 标成购买失败但没退款的行，
     * 在本端点里会被<b>补建退款单</b>，不会漏掉。</p>
     *
     * <p><b>权限独立</b>（不复用 {@code advance}）：推进履约状态与动客人的钱是两件事，
     * 得能分开授权 —— 店员可以推状态，退款要更高权限。</p>
     *
     * <p>返回体<b>分两组计数</b>：履约侧（标没标上）与退款侧（钱退没退）。
     * 前端要分开显示 —— 合成一个「成功 N 行」会掩盖「状态标了但钱没退成功」这种最危险的情况。</p>
     *
     * @return 两组计数 + 逐行明细；一行都标不上且无款可退时抛 4111
     */
    @SaCheckPermission("gz:jp:fulfill:markFailed")
    @Log(title = "拼团标记购买失败并退款", businessType = BusinessType.UPDATE)
    @RepeatSubmit()
    @PostMapping("/mark-failed")
    public R<GzJpMarkFailedResultVO> markFailed(@Validated @RequestBody GzJpMarkFailedBo bo) {
        return R.ok("操作成功", refundService.markPurchaseFailedAndRefund(
            bo, LoginHelper.getUserId(), LoginHelper.getUsername()));
    }
}
