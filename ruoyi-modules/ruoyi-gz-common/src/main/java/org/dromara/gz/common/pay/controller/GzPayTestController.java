package org.dromara.gz.common.pay.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.R;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.common.web.core.BaseController;
import org.dromara.gz.common.pay.config.WechatPayProperties;
import org.dromara.gz.common.pay.domain.bo.CreateOrderBo;
import org.dromara.gz.common.pay.domain.bo.GzPayTestCreateBo;
import org.dromara.gz.common.pay.domain.entity.GzPayTransaction;
import org.dromara.gz.common.pay.domain.vo.MpPayParamsVO;
import org.dromara.gz.common.pay.mapper.GzPayTransactionMapper;
import org.dromara.gz.common.pay.service.IGzPayTransactionService;
import org.dromara.gz.common.pay.service.internal.IWechatPayClient.NotifyContext;
import org.dromara.gz.common.pay.service.internal.MockWechatPayClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * GZ-PAY-001 admin 端支付测试工具（AC 4 / AC 5 mock 闭环驱动）。
 *
 * <p>路径前缀 {@code /system/gz/pay/test}。权限 {@code gz:pay:test}（menu_id 5103，仅 owner，dev/staging）。
 * 风险 R8：prod 上线后关闭此 perm。</p>
 *
 * <p><b>mock 闭环</b>（AC 9 / AC 11 未到位场景）：商户号未下证时，{@code /simulate-callback} 端点
 * 在 mock profile 下用 {@link MockWechatPayClient#buildMockCallbackBody} 构造一条回调 body，
 * 直接驱动 {@link IGzPayTransactionService#handlePaymentNotify}，让 mp result.vue 轮询能看到
 * status=paid —— 等价真实微信回调，用于 dev/staging 端到端联调（不连真实微信）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-PAY-001)
 */
@Slf4j
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/system/gz/pay/test")
public class GzPayTestController extends BaseController {

    private final IGzPayTransactionService transactionService;
    private final GzPayTransactionMapper transactionMapper;
    private final WechatPayProperties payProperties;
    /** mock client 仅在 mock profile 存在；用 ObjectProvider 软依赖（real profile 下为空） */
    private final ObjectProvider<MockWechatPayClient> mockClientProvider;

    /** 发起测试支付单（返回 mp 端 5 参 + out_trade_no） */
    @SaCheckPermission("gz:pay:test")
    @PostMapping("/create-order")
    public R<MpPayParamsVO> createOrder(@Validated @RequestBody GzPayTestCreateBo bo) {
        Long loginUserId = LoginHelper.getUserId();
        log.info("[gz-pay-test] create-order amount={} by userId={}", bo.getAmountCent(), loginUserId);
        return R.ok(transactionService.createTestOrder(bo, loginUserId));
    }

    /**
     * 发起业务支付单（PAY-101 AC 1 / AC 10 mock 全链路自测驱动入口）。
     *
     * <p>dev/staging 联调用：构造 {@link CreateOrderBo}（business_type=preorder / gacha + 模拟
     * business_order_no）走 {@code createBusinessOrder} → 拿 out_trade_no → 再调
     * {@code /simulate-callback} 驱动 SPI 路由 + 出单。<b>生产</b>业务下单走 ORD-104 / GACHA 各自的
     * 下单事务（在那里注入 service 调 {@code createBusinessOrder}），不走本测试入口（R8：prod 关 perm）。</p>
     *
     * @param bo 业务建单入参
     * @return mp 端 5 参 + out_trade_no
     */
    @SaCheckPermission("gz:pay:test")
    @PostMapping("/create-business-order")
    public R<MpPayParamsVO> createBusinessOrder(@Validated @RequestBody CreateOrderBo bo) {
        log.info("[gz-pay-test] create-business-order business_type={} business_order_no={} amount={}",
            bo.getBusinessType(), bo.getBusinessOrderNo(), bo.getAmountCent());
        return R.ok(transactionService.createBusinessOrder(bo));
    }

    /**
     * 模拟微信回调（AC 9 mock 闭环；仅 mock profile 可用）。
     *
     * <p>构造 mock 回调 body → 走与真实回调同一条处理链路 handlePaymentNotify。
     * real profile 下 mockClientProvider 为空 → 拒绝（真实回调走 /api/pay/v3/notify）。</p>
     *
     * @param outTradeNo 测试单 out_trade_no
     * @return 处理结果
     */
    @SaCheckPermission("gz:pay:test")
    @PostMapping("/simulate-callback")
    public R<Void> simulateCallback(@RequestParam String outTradeNo) {
        if (!payProperties.isMock()) {
            throw new ServiceException("real profile 不支持模拟回调，请走真实微信回调 /api/pay/v3/notify");
        }
        MockWechatPayClient mockClient = mockClientProvider.getIfAvailable();
        if (mockClient == null) {
            throw new ServiceException("mock client 未激活，无法模拟回调");
        }
        GzPayTransaction tx = transactionMapper.selectByOutTradeNo(outTradeNo);
        if (tx == null) {
            throw new ServiceException("订单不存在: " + outTradeNo);
        }
        String mockTxId = "mock_wx_txn_" + outTradeNo;
        String body = mockClient.buildMockCallbackBody(outTradeNo, mockTxId, tx.getAmountCent());
        NotifyContext ctx = new NotifyContext("0", "mock_nonce", "mock_sig", "mock_serial", body);
        boolean ok = transactionService.handlePaymentNotify(ctx);
        log.info("[gz-pay-test] simulate-callback out_trade_no={} ok={}", outTradeNo, ok);
        return ok ? R.ok() : R.fail("模拟回调处理失败");
    }
}
