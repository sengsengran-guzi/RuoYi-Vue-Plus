package org.dromara.gz.common.pay.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.common.web.core.BaseController;
import org.dromara.gz.common.pay.domain.bo.GzPayPayoutQueryBo;
import org.dromara.gz.common.pay.domain.vo.GzPayPayoutTransactionVO;
import org.dromara.gz.common.pay.service.IGzPayPayoutService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * GZ-PAY-105 admin 端反向打款单管理（列表 + 详情）。
 *
 * <p>路径前缀 {@code /system/gz/pay/payout}。权限 {@code gz:pay:payout:list / query}
 * （menu_id 5111 / 5112，仅 owner —— 反向出账属敏感资金操作）。本卡仅查看（建单/触发在
 * D14 GZ-RECYCLE-003 店员核对端）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-PAY-105)
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/system/gz/pay/payout")
public class GzPayPayoutController extends BaseController {

    private final IGzPayPayoutService payoutService;

    /** 分页列表（筛 status / out_payout_no / business_order_no / payout_id） */
    @SaCheckPermission("gz:pay:payout:list")
    @GetMapping("/list")
    public TableDataInfo<GzPayPayoutTransactionVO> list(GzPayPayoutQueryBo query, PageQuery pageQuery) {
        return payoutService.selectPageList(query, pageQuery);
    }

    /** 详情 */
    @SaCheckPermission("gz:pay:payout:query")
    @GetMapping("/{id}")
    public R<GzPayPayoutTransactionVO> detail(@PathVariable Long id) {
        GzPayPayoutTransactionVO vo = payoutService.getById(id);
        if (vo == null) {
            return R.fail("反向打款单不存在");
        }
        return R.ok(vo);
    }
}
