package org.dromara.gz.common.pay.controller.applet;

import cn.dev33.satoken.annotation.SaCheckLogin;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.gz.common.pay.domain.vo.GzPayTransactionVO;
import org.dromara.gz.common.pay.service.IGzPayTransactionService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * GZ-PAY-001 mp 端支付状态轮询（AC 5，决策 D7）。
 *
 * <p>路径前缀 {@code /app/gz/pay}（sensenran C 端 mp 前缀，对齐 GzBeanBookingMpController）。
 * 需登录态（{@link SaCheckLogin}）—— 用户只能查自己发起的测试单（轮询自身订单状态）。</p>
 *
 * <p>result.vue 支付后轮询本端点等回调到达 status=paid（微信回调有 1-5s 延迟，mp 不挂起等，轮询友好）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-PAY-001)
 */
@SaCheckLogin
@RequiredArgsConstructor
@RestController
@RequestMapping("/app/gz/pay")
public class GzPayMpController {

    private final IGzPayTransactionService transactionService;

    /**
     * 查支付单状态（mp result.vue 轮询）。
     *
     * @param outTradeNo 业务订单号
     * @return 订单 VO（含 status：pending / paid / timeout / ...）
     */
    @GetMapping("/transaction/{outTradeNo}/status")
    public R<GzPayTransactionVO> status(@PathVariable String outTradeNo) {
        GzPayTransactionVO vo = transactionService.getByOutTradeNo(outTradeNo);
        if (vo == null) {
            return R.fail("订单不存在");
        }
        return R.ok(vo);
    }
}
