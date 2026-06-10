package org.dromara.gz.common.pay.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.common.web.core.BaseController;
import org.dromara.gz.common.pay.domain.bo.GzPayPayoutQueryBo;
import org.dromara.gz.common.pay.domain.vo.GzPayPayoutTransactionVO;
import org.dromara.gz.common.pay.service.IGzPayPayoutService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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

    /**
     * 失败重试（ADR-0006 旁路 failed 可重试，doc/10 §14.N6）：owner 对 payout_failed 单触发重试。
     *
     * <p>{@code failed → created} 重置后重新受理（新 batch_id）。<b>仅 owner 手动</b>（敏感资金操作，
     * 权限 {@code gz:pay:payout:retry}，menu 5113）。回收单 paying→paid 的回写由 RECYCLE-003 钩子收敛。</p>
     *
     * <pre>POST /system/gz/pay/payout/{businessOrderNo}/retry</pre>
     */
    @SaCheckPermission("gz:pay:payout:retry")
    @Log(title = "反向打款失败重试", businessType = BusinessType.UPDATE)
    @PostMapping("/{businessOrderNo}/retry")
    public R<GzPayPayoutTransactionVO> retry(@PathVariable String businessOrderNo) {
        return R.ok(payoutService.retryPayout(businessOrderNo, "谷子回收返现"));
    }

    /**
     * 主动查单一次（ADR-0006 §3 主动查单优先）：owner 对 processing 单手动触发查单推进。
     *
     * <pre>POST /system/gz/pay/payout/{businessOrderNo}/query</pre>
     */
    @SaCheckPermission("gz:pay:payout:retry")
    @Log(title = "反向打款主动查单", businessType = BusinessType.UPDATE)
    @PostMapping("/{businessOrderNo}/query")
    public R<GzPayPayoutTransactionVO> queryOnce(@PathVariable String businessOrderNo) {
        GzPayPayoutTransactionVO vo = payoutService.queryAndAdvanceByBusinessOrderNo(businessOrderNo);
        if (vo == null) {
            return R.fail("该业务单无在途打款单");
        }
        return R.ok(vo);
    }
}
