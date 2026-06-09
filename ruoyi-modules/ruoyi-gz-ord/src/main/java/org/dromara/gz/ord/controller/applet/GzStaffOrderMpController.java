package org.dromara.gz.ord.controller.applet;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.R;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.gz.ord.domain.bo.GzAdminOrderQueryBo;
import org.dromara.gz.ord.domain.vo.GzUnifiedOrderVo;
import org.dromara.gz.ord.service.IGzUnifiedOrderService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * GZ-ADMIN-201 mp 店员端「订单查看」（只读，双下沉，ADR-0004 落地）。
 *
 * <p>路径前缀 {@code /app/gz/staff/orders}（mp {@code /app/} 域 + staff 命名段，与 C 端顾客
 * {@code /app/gz/ord/order}、admin {@code /system/gz/ord/orders} 三者区分）。</p>
 *
 * <p><b>权限</b>：{@code @SaCheckPermission("gz:ord:view")} —— 与 admin 同一套 ruoyi RBAC（ADR-0004
 * 底座：mp wx-login 时把绑定 sys_user 的角色/菜单权限装进 app_user 会话）。三态语义：
 * <ul>
 *   <li>绑定且授权 gz:ord:view 的店员 token → 200 返回订单（AC4①）</li>
 *   <li>纯顾客 app_user token（staff_user_id 为 NULL，会话不带任何 gz:* 权限）→ sa-token 403（AC4②）</li>
 *   <li>绑定但未授权 gz:ord:view 的店员 token → sa-token 403（AC4③）</li>
 * </ul>
 * 停用 / 解绑 sys_user 由 GZ-SYS-007 {@code SysUserStaffKickoutAspect} + {@code MpStaffPermissionService}
 * 即时踢 token（再调本端点 → 401，AC5）—— 本卡<b>零底座改动</b>。</p>
 *
 * <p><b>架构</b>：本 controller 落在 {@code ruoyi-gz-ord} 而非任务卡 Tech 段建议的 gz-user ——
 * 因为复用的聚合逻辑 {@link IGzUnifiedOrderService#listForAdmin}（GZ-ADMIN-103）位于 gz-ord，
 * 且 {@code gz-ord → gz-user} 已是单向依赖（预购下单写地址 snapshot），反向让 gz-user 依赖 gz-ord
 * 会构成 Maven 循环依赖（GZ-USER-101 报告已记同根因）。店员端聚合查询不带 user_id 过滤，
 * 与 admin 端 {@code listForAdmin} 完全同源 —— <b>不另造平行聚合 service / 不另写 UNION SQL</b>
 * （CLAUDE.md 逻辑复用纪律 + ticket AC2）。</p>
 *
 * <p><b>只读</b>：本 controller 仅 GET（列表 + 详情），<b>无任何写端点</b>（无退款 / 无物流推进 /
 * 无改单号 / 无确认收货，AC3）—— 退款属 GZ-PAY-103 owner 端、物流推进属 D11 GZ-ADMIN-104。</p>
 *
 * <p>关联文档：doc/_adr/0004 §决策 1 / doc/10 §8·§9 / doc/11 §8.1·§8.2 / GZ-ADMIN-201 任务卡</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-ADMIN-201)
 */
@Slf4j
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/app/gz/staff/orders")
public class GzStaffOrderMpController {

    private final IGzUnifiedOrderService unifiedOrderService;

    /**
     * 店员端三类聚合订单分页列表（只读，AC1/AC2）。
     *
     * <p>复用 {@link IGzUnifiedOrderService#listForAdmin}（GZ-ADMIN-103，不带 user_id 过滤、
     * tenant_id 走 ruoyi 拦截器）。{@code bizType} 取值 all / preorder / gacha（默认 all）——
     * 店员端只看预购 + 扭蛋两类业务订单，{@code test} 单不在店员视角语义内（前端 tab 仅 all/预购/扭蛋）。</p>
     *
     * <pre>
     * GET /app/gz/staff/orders?bizType=all&amp;status=&amp;logisticsStatus=&amp;userKeyword=&amp;orderNo=&amp;pageNum=1&amp;pageSize=10
     * Headers: Authorization: Bearer &lt;sa-token&gt; / clientid: mp-applet-sensenran-guzi
     *
     * 200 OK  { "code":200, "rows":[ GzUnifiedOrderVo... ], "total": 12, "msg":"查询成功" }
     * 403     绑定但未授权 / 纯顾客（sa-token NotPermissionException）
     * 401     未登录 / 停用解绑后会话被踢
     * </pre>
     *
     * @param bizType         业务类型 all / preorder / gacha（默认 all；映射 query.businessType，all → 不过滤）
     * @param status          统一业务状态 chip（doc/11 §8.2 to_pay/to_ship/shipping/done/cancelled/refunded，空 = 不过滤）
     * @param logisticsStatus 物流态 in_japan / in_china_dispatching / delivered（空 = 不过滤）
     * @param userKeyword     用户关键词（昵称 / openid 模糊）
     * @param orderNo         订单号（business_order_no 前缀）
     * @param pageQuery       分页参数（pageNum / pageSize）
     * @return 统一订单 VO 分页（rows = {@link GzUnifiedOrderVo}，时间倒序）
     */
    @SaCheckPermission("gz:ord:view")
    @GetMapping
    public TableDataInfo<GzUnifiedOrderVo> list(
        @org.springframework.web.bind.annotation.RequestParam(value = "bizType", required = false) String bizType,
        @org.springframework.web.bind.annotation.RequestParam(value = "status", required = false) String status,
        @org.springframework.web.bind.annotation.RequestParam(value = "logisticsStatus", required = false) String logisticsStatus,
        @org.springframework.web.bind.annotation.RequestParam(value = "userKeyword", required = false) String userKeyword,
        @org.springframework.web.bind.annotation.RequestParam(value = "orderNo", required = false) String orderNo,
        PageQuery pageQuery) {
        GzAdminOrderQueryBo query = new GzAdminOrderQueryBo();
        // bizType=all（或空 / 非法）→ businessType 留空 = 不按 business_type 过滤（service 口径）
        if (bizType != null && !"all".equalsIgnoreCase(bizType.trim()) && !bizType.isBlank()) {
            query.setBusinessType(bizType.trim());
        }
        query.setBusinessStatus(blankToNull(status));
        query.setLogisticsStatus(blankToNull(logisticsStatus));
        query.setUserKeyword(blankToNull(userKeyword));
        query.setOrderNo(blankToNull(orderNo));
        log.info("[staff-orders-mp] list operator={} bizType={} status={} logisticsStatus={} userKeyword={} orderNo={}",
            LoginHelper.getUsername(), bizType, status, logisticsStatus,
            userKeyword == null ? null : "***", orderNo);
        return unifiedOrderService.listForAdmin(query, pageQuery);
    }

    /**
     * 店员端订单详情（只读，AC7；按 gz_pay_transaction.id 取，含 snapshot 差异块 + 物流 2 态）。
     *
     * <p>复用 {@link IGzUnifiedOrderService#getDetailForAdmin}（GZ-ADMIN-103）。扭蛋订单返
     * 获得物 snapshot（prizeName / prizeImageUrl / rarity）+ machineName；预购订单返
     * productName / specName + 收货 snapshot。物流走 C1 2 态 + 终态只读字段。</p>
     *
     * <pre>
     * GET /app/gz/staff/orders/{transactionId}
     * 200 OK  { "code":200, "data": GzUnifiedOrderVo }
     * 不存在  R.fail("订单不存在：{id}")
     * </pre>
     *
     * @param transactionId 支付交易行 id（= 列表 VO 的 transactionId）
     * @return 统一订单 VO（含差异块）
     */
    @SaCheckPermission("gz:ord:view")
    @GetMapping("/{transactionId}")
    public R<GzUnifiedOrderVo> getInfo(@NotNull @PathVariable Long transactionId) {
        log.info("[staff-orders-mp] detail operator={} transactionId={}",
            LoginHelper.getUsername(), transactionId);
        GzUnifiedOrderVo vo = unifiedOrderService.getDetailForAdmin(transactionId);
        if (vo == null) {
            return R.fail("订单不存在：" + transactionId);
        }
        return R.ok(vo);
    }

    /** 空串 / 全空白 → null（避免空字符串被 service 当成有效过滤值）。 */
    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }
}
