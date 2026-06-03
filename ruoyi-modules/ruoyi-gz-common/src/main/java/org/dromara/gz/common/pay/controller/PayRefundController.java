package org.dromara.gz.common.pay.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.common.web.core.BaseController;
import org.dromara.gz.common.pay.domain.bo.RefundApplyBo;
import org.dromara.gz.common.pay.domain.vo.GzPayRefundVO;
import org.dromara.gz.common.pay.service.IPayRefundService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * GZ-PAY-103 admin 端退款申请 + 退款记录（仅 owner，敏感运营）。
 *
 * <p>路径前缀 {@code /system/gz/pay/refund}。权限 {@code gz:pay:refund:apply / list}（menu_id 5106/5107）。
 * <b>仅全额退款</b>（doc/10 §6.E5）：申请接口无金额入参，退款额由系统取原单 amount_cent（强约束 #1 / 决策 D2）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-PAY-103)
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/system/gz/pay/refund")
public class PayRefundController extends BaseController {

    private final IPayRefundService refundService;

    /**
     * 发起全额退款（owner 触发，AC 2）。body {@code { transactionId, reason }}，无金额入参。
     */
    @SaCheckPermission("gz:pay:refund:apply")
    @Log(title = "支付退款", businessType = BusinessType.OTHER)
    @PostMapping("/apply")
    public R<GzPayRefundVO> apply(@Validated @RequestBody RefundApplyBo bo) {
        String triggeredBy = LoginHelper.getUsername();
        return R.ok(refundService.apply(bo, triggeredBy));
    }

    /**
     * 退款记录分页列表（AC 5）。筛 out_trade_no / status。
     */
    @SaCheckPermission("gz:pay:refund:list")
    @GetMapping("/list")
    public TableDataInfo<GzPayRefundVO> list(@RequestParam(required = false) String outTradeNo,
                                             @RequestParam(required = false) String status,
                                             PageQuery pageQuery) {
        return refundService.selectPageList(outTradeNo, status, pageQuery);
    }
}
