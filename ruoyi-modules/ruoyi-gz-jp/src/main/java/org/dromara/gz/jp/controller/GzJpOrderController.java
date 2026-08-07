package org.dromara.gz.jp.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.R;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.common.web.core.BaseController;
import org.dromara.gz.jp.domain.bo.GzJpOrderQueryBo;
import org.dromara.gz.jp.domain.vo.GzJpOrderAdminDetailVO;
import org.dromara.gz.jp.domain.vo.GzJpOrderAdminVO;
import org.dromara.gz.jp.service.IGzJpOrderAdminService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * GZ-JP-109 订单管理（admin 端，UI:admin.order）—— <b>★ 全只读，只有 GET</b>。
 *
 * <p>路径前缀 {@code /system/gz/jp/order}。客人侧订单读端点在
 * {@code /app/gz/jp/order/*}（GZ-JP-105），两套 VO 不共用 ——
 * 本控制器下发客人身份与支付流水，那些<b>一个字节都不能给客人</b>。</p>
 *
 * <p><b>为什么这里一个写端点都没有</b>（AC「列表与详情均只读，无写操作按钮」）：
 * 订单的三条写路径各有归属 —— 下单在 mp、支付回调在 GZ-PAY SPI、
 * 履约推进与发货在 <b>履约看板</b>（{@code /system/gz/jp/fulfill/advance|ship}，GZ-JP-106）。
 * 查单页开一个写口子，就等于给了一条绕过状态机守卫与批量语义的路。
 * 要改货的状态，去履约看板。</p>
 *
 * <p><b>权限</b>（菜单 + 按钮 seed 在本卡的迁移里，menu_id <b>14020 段</b>）：
 * {@code gz:jp:order:list} —— 列表与详情共用。只读页不拆第二个权限位，
 * 拆了只会让「能看列表但点不开详情」这种没人想要的组合成为可能。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-JP-109)
 */
@Slf4j
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/system/gz/jp/order")
public class GzJpOrderController extends BaseController {

    private final IGzJpOrderAdminService orderAdminService;

    /**
     * 订单列表（UI:admin.order 列表：订单号 / 客人 / 款数 / 实付 / 订单状态 / 下单时间）。
     *
     * <pre>
     * GET /system/gz/jp/order/list?pageNum=1&amp;pageSize=20
     *     &amp;orderNo=000123                       // 模糊（客人只报得出后几位）
     *     &amp;userId=14 | &amp;keyword=小王              // 客人；keyword 无匹配返回 total=0
     *     &amp;businessStatus=created&amp;businessStatus=cancelled   // 多选，重复参数名
     *     &amp;beginDate=2026-08-01&amp;endDate=2026-08-07           // 按【下单时间】，含首尾整天
     * </pre>
     *
     * <p><b>★ 与履约看板不同，本列表不过滤付款状态</b>：{@code created} / {@code cancelled}
     * 的订单必须出现（资金视角查单）。</p>
     */
    @SaCheckPermission("gz:jp:order:list")
    @GetMapping("/list")
    public TableDataInfo<GzJpOrderAdminVO> list(GzJpOrderQueryBo query, PageQuery pageQuery) {
        return orderAdminService.selectAdminPage(query, pageQuery);
    }

    /**
     * 订单详情（UI:admin.order 详情抽屉）—— 订单头 + 逐行商品及履约状态 + 收货地址，<b>全只读</b>。
     *
     * <pre>
     * GET /system/gz/jp/order/{id}
     * </pre>
     *
     * <p>订单不存在 / 已删 → 500 {@code 订单不存在}（admin 侧不必像 mp 那样做同码不泄漏处理）。</p>
     */
    @SaCheckPermission("gz:jp:order:list")
    @GetMapping("/{id}")
    public R<GzJpOrderAdminDetailVO> getInfo(@PathVariable Long id) {
        return R.ok(orderAdminService.getAdminDetail(id));
    }
}
