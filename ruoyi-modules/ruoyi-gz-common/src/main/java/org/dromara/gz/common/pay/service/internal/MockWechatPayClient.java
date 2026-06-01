package org.dromara.gz.common.pay.service.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.dromara.gz.common.pay.config.WechatPayProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 微信支付通道 mock 实现（GZ-PAY-001 AC 9）。
 *
 * <p><b>何时生效</b>：{@code gz.pay.client.mode != real}（dev 默认 + 单测 + 商户号未到位的 staging）。
 * 通过 {@link ConditionalOnProperty}（matchIfMissing=true）保证缺省即 mock，不依赖真实证书。</p>
 *
 * <p><b>行为</b>：</p>
 * <ul>
 *   <li>{@link #createJsapiOrder} → 返回固定格式 mock prepay_id（不连微信）</li>
 *   <li>{@link #buildPayParams} → 返回固定占位 5 参（mp dev 端只验流程）</li>
 *   <li>{@link #parseAndVerifyNotify} → 不验签，直接把 body 当已解密 JSON 解析；
 *       验签失败场景由 {@link #buildMockCallbackBody} 构造的 body 是否合法触发</li>
 * </ul>
 *
 * <p>验签失败的单测路径：测试构造非法 body（或调用方注入 throw），不依赖此 mock 内部判断。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-PAY-001)
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "gz.pay", name = "client-mode", havingValue = "mock", matchIfMissing = true)
public class MockWechatPayClient implements IWechatPayClient {

    /** mock prepay_id 前缀（便于 admin / 日志辨识非真实通道） */
    public static final String MOCK_PREPAY_PREFIX = "mock_prepay_";

    private final ObjectMapper objectMapper = new ObjectMapper();

    public MockWechatPayClient(WechatPayProperties properties) {
        log.warn("[gz-pay] MockWechatPayClient 已激活（client-mode != real）—— 不连真实微信通道，仅供 dev/单测/商户号未到位降级。");
    }

    @Override
    public String createJsapiOrder(UnifiedOrderRequest req) {
        String prepayId = MOCK_PREPAY_PREFIX + req.outTradeNo();
        log.info("[gz-pay-mock] createJsapiOrder out_trade_no={} amount={} → prepay_id={}",
            req.outTradeNo(), req.amountCent(), prepayId);
        return prepayId;
    }

    @Override
    public JsapiPayParams buildPayParams(String prepayId) {
        return new JsapiPayParams(
            String.valueOf(System.currentTimeMillis() / 1000),
            UUID.randomUUID().toString().replace("-", ""),
            "prepay_id=" + prepayId,
            "RSA",
            "mock_pay_sign_" + prepayId);
    }

    @Override
    public CallbackResult parseAndVerifyNotify(NotifyContext ctx) {
        // mock 不验签：把 body 当已解密 JSON 解析。body 非法 → throw（模拟验签/解析失败路径）。
        try {
            ObjectNode node = (ObjectNode) objectMapper.readTree(ctx.body());
            String transactionId = node.path("transaction_id").asText(null);
            String outTradeNo = node.path("out_trade_no").asText(null);
            String tradeState = node.path("trade_state").asText("SUCCESS");
            Long payerTotal = node.has("payer_total") ? node.get("payer_total").asLong() : null;
            if (outTradeNo == null || transactionId == null) {
                throw new WechatPayVerifyException("mock 回调 body 缺 out_trade_no / transaction_id");
            }
            return new CallbackResult(transactionId, outTradeNo, tradeState, payerTotal, null, ctx.body());
        } catch (WechatPayVerifyException e) {
            throw e;
        } catch (Exception e) {
            throw new WechatPayVerifyException("mock 回调 body 解析失败: " + e.getMessage(), e);
        }
    }

    /**
     * 构造 mock 回调 body（测试 / mp test.vue 模拟支付成功用）。
     *
     * @param outTradeNo    业务订单号
     * @param transactionId 模拟微信交易号
     * @param payerTotal    支付金额（分）
     * @return JSON body 字符串
     */
    public String buildMockCallbackBody(String outTradeNo, String transactionId, long payerTotal) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("out_trade_no", outTradeNo);
        node.put("transaction_id", transactionId);
        node.put("trade_state", "SUCCESS");
        node.put("payer_total", payerTotal);
        return node.toString();
    }
}
