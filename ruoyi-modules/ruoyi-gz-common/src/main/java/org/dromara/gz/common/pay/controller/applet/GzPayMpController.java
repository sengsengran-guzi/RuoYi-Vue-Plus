package org.dromara.gz.common.pay.controller.applet;

import cn.dev33.satoken.annotation.SaCheckLogin;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.R;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.gz.common.pay.domain.vo.GzPayStatusVO;
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
 * <p><b>D16 B2 IDOR 收口</b>：out_trade_no 为连续可枚举序号，原实现仅 {@link SaCheckLogin}
 * 无归属校验 + 返回完整 VO（含 openid/transactionId），任意登录用户可枚举读他人 PII。
 * 现加 userId 归属校验（非本人 403）+ 投影窄 VO（剔除 openid/prepayId/userId/feeCent）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-PAY-001)
 */
@Slf4j
@SaCheckLogin
@RequiredArgsConstructor
@RestController
@RequestMapping("/app/gz/pay")
public class GzPayMpController {

    private final IGzPayTransactionService transactionService;

    /**
     * 查支付单状态（mp result.vue 轮询）。仅本人发起的支付单可查。
     *
     * @param outTradeNo 业务订单号
     * @return 窄状态 VO（status / amountCent / transactionId / paidTime）
     */
    @GetMapping("/transaction/{outTradeNo}/status")
    public R<GzPayStatusVO> status(@PathVariable String outTradeNo) {
        Long userId = LoginHelper.getUserId();
        if (userId == null) {
            return R.fail(401, "未登录");
        }
        GzPayTransactionVO vo = transactionService.getByOutTradeNo(outTradeNo);
        if (vo == null) {
            return R.fail("订单不存在");
        }
        // 归属校验：仅本人发起的支付单可查（防 IDOR 枚举越权读 PII）
        if (!String.valueOf(userId).equals(String.valueOf(vo.getUserId()))) {
            log.warn("[pay-mp] status forbidden userId={} but txn.userId={} outTradeNo={}",
                userId, vo.getUserId(), outTradeNo);
            return R.fail(403, "无权查看该订单");
        }
        return R.ok(GzPayStatusVO.from(vo));
    }
}
