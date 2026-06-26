package org.dromara.gz.common.pay.shipping.impl;

import lombok.extern.slf4j.Slf4j;
import org.dromara.gz.common.pay.shipping.WxShippingClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 微信发货信息录入 mock 实现。
 *
 * <p>启用条件：{@code wx.miniapp.appid=wxMOCK}（含默认值，{@code matchIfMissing=true}）。仅打日志返成功，
 * 让 dev 的 mock-pay 全流程（{@code /app/gz/test/pay/simulate-callback}）走到发货上报而不触网，
 * 并使 {@code gz_pay_shipping_order} 状态推进到 success 可被 E2E 断言。</p>
 *
 * @author kevin-coder (sensenran-guzi)
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "wx.miniapp", name = "appid", havingValue = "wxMOCK", matchIfMissing = true)
public class WxMockShippingClient implements WxShippingClient {

    @Override
    public UploadResult uploadShippingInfo(UploadCommand cmd) {
        log.info("[wx-shipping-mock] 模拟发货信息上报成功 transaction_id={} logisticsType={} itemDesc={}",
            cmd.transactionId(), cmd.logisticsType(), cmd.itemDesc());
        return UploadResult.ok();
    }
}
