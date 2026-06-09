package org.dromara.gz.ord.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.R;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.common.web.core.BaseController;
import org.dromara.gz.ord.domain.bo.GzAdminOrderQueryBo;
import org.dromara.gz.ord.domain.vo.GzUnifiedOrderVo;
import org.dromara.gz.ord.service.IGzUnifiedOrderService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * GZ-ADMIN-103 admin 三类聚合订单管理（preorder / gacha / test）。
 *
 * <p>路径前缀 {@code /system/gz/ord/orders}（复数，与 GZ-ORD-105 单业务 {@code /system/gz/ord/order}
 * 区分）。聚合走 {@code gz_pay_transaction} 按 {@code business_type} 回查业务订单表（doc/11 §8.1）。</p>
 *
 * <p>权限（DDL menu_id 11007-11019，GZ-ADMIN 扩展段，仅租户 1001 owner role_id=100）：</p>
 * <ul>
 *   <li>{@code gz:ord:orders:list}  — 三类聚合列表（11008）</li>
 *   <li>{@code gz:ord:orders:query} — 详情查看（11009）</li>
 * </ul>
 *
 * <p><b>退款</b>走 GZ-PAY-103 已落地的 {@link org.dromara.gz.common.pay.controller.PayRefundController}
 * （{@code POST /system/gz/pay/refund/apply}，perm {@code gz:pay:refund:apply}），本 controller
 * 不重复暴露 —— admin 前端详情「申请退款」按钮直接调 PAY-103 接口（决策 D5）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-ADMIN-103)
 */
@Slf4j
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/system/gz/ord/orders")
public class GzOrdOrdersController extends BaseController {

    private final IGzUnifiedOrderService unifiedOrderService;

    /**
     * 三类聚合订单分页列表（AC2/AC4，只读）。
     *
     * <p>筛选：businessType（preorder/gacha/test，空=全部）/ businessStatus（统一 chip）/
     * logisticsStatus / userKeyword（昵称 / openid 模糊）/ 时间范围 / orderNo（前缀）。</p>
     */
    @SaCheckPermission("gz:ord:orders:list")
    @GetMapping("/list")
    public TableDataInfo<GzUnifiedOrderVo> list(GzAdminOrderQueryBo query, PageQuery pageQuery) {
        return unifiedOrderService.listForAdmin(query, pageQuery);
    }

    /**
     * 订单详情（AC5，只读；按 gz_pay_transaction.id 取，含 snapshot 差异块 + 地址 + 物流）。
     */
    @SaCheckPermission("gz:ord:orders:query")
    @GetMapping("/{transactionId}")
    public R<GzUnifiedOrderVo> getInfo(@NotNull @PathVariable Long transactionId) {
        GzUnifiedOrderVo vo = unifiedOrderService.getDetailForAdmin(transactionId);
        if (vo == null) {
            return R.fail("订单不存在：" + transactionId);
        }
        return R.ok(vo);
    }
}
