package org.dromara.gz.ord.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.R;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.common.web.core.BaseController;
import org.dromara.gz.ord.domain.bo.GzOrdOrderQueryBo;
import org.dromara.gz.ord.domain.vo.GzOrdOrderAdminVO;
import org.dromara.gz.ord.service.IGzOrdOrderService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * GZ-ORD-105 预购订单 admin 只读查询（运营 / 客服查单）。
 *
 * <p>路径前缀 {@code /system/gz/ord/order} — 与 ruoyi 自带 {@code /system/...} 域隔离；mp 端订单
 * 列表 / 详情走 {@code /app/gz/ord/order/...}（GzOrdOrderMpController）。</p>
 *
 * <p>权限（DDL menu_id 9100 段，仅租户 1001 owner role_id=100）：</p>
 * <ul>
 *   <li>{@code gz:ord:order:list}  — 列表（9111）</li>
 *   <li>{@code gz:ord:order:query} — 详情查看（9112）</li>
 * </ul>
 *
 * <p><b>本卡 admin 仅订单查询（只读）</b>（强约束 #10）：物流推进 / 录单号 = GZ-ADMIN-104（下沉 mp 店员端），
 * 退款 = GZ-PAY-103；本 controller <b>不</b>含写接口。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-ORD-105)
 */
@Slf4j
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/system/gz/ord/order")
public class GzOrdOrderController extends BaseController {

    private final IGzOrdOrderService ordOrderService;

    /**
     * 分页查询预购订单（只读；businessStatus 精确 + userPhone 模糊 + orderNo 模糊；关联 gz_user 取手机号）。
     */
    @SaCheckPermission("gz:ord:order:list")
    @GetMapping("/list")
    public TableDataInfo<GzOrdOrderAdminVO> list(GzOrdOrderQueryBo query, PageQuery pageQuery) {
        return ordOrderService.pageForAdmin(query, pageQuery);
    }

    /**
     * 订单详情（只读；含 snapshot 解析 + 物流字段 + 用户手机号）。
     */
    @SaCheckPermission("gz:ord:order:query")
    @GetMapping("/{id}")
    public R<GzOrdOrderAdminVO> getInfo(@NotNull @PathVariable Long id) {
        GzOrdOrderAdminVO vo = ordOrderService.getDetailForAdmin(id);
        if (vo == null) {
            return R.fail("订单不存在：" + id);
        }
        return R.ok(vo);
    }
}
