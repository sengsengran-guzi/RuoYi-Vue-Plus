package org.dromara.gz.common.pay.shipping.impl;

import lombok.extern.slf4j.Slf4j;
import org.dromara.gz.common.pay.shipping.WxShippingClient;
import org.dromara.gz.common.wechat.WxAdapterDispatcher;
import org.springframework.stereotype.Component;

/**
 * 微信发货信息录入 mock 实现。
 *
 * <p><b>装配</b>（ADR-0019 §1）：无条件注册；当前 clientid 对应的小程序为 mock 时由
 * {@link WxAdapterDispatcher} 运行时选中。仅打日志返成功，让 dev 的 mock-pay 全流程
 * （{@code /app/gz/test/pay/simulate-callback}）走到发货上报而不触网，并使
 * {@code gz_pay_shipping_order} 状态推进到 success 可被 E2E 断言。</p>
 *
 * @author kevin-coder (sensenran-guzi)
 */
@Slf4j
@Component
public class WxMockShippingClient implements WxShippingClient {

    @Override
    public UploadResult uploadShippingInfo(UploadCommand cmd) {
        log.info("[wx-shipping-mock] 模拟发货信息上报成功 transaction_id={} clientId={} logisticsType={} "
                + "deliveryMode={} allDelivered={} packages={} itemDesc={}",
            cmd.transactionId(), cmd.clientId(), cmd.logisticsType(), cmd.deliveryMode(),
            cmd.allDelivered(), cmd.packages() == null ? 0 : cmd.packages().size(), cmd.itemDesc());
        return UploadResult.ok();
    }
}
