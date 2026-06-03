package org.dromara.gz.common.pay.service.spi;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.dromara.gz.common.pay.domain.entity.GzPayRefund;
import org.dromara.gz.common.pay.domain.entity.GzPayTransaction;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 退款回调业务分发注册中心（GZ-PAY-103 AC 4，决策 D1）。
 *
 * <p><b>与支付 {@link PayCallbackDispatcher} 平行且独立 key 空间</b>（强约束 #4 / README R2）：本类只
 * 收集 {@link IRefundCallbackHandler} Bean（退款 SPI），按 {@code supportedBusinessType()} index 成
 * 退款路由表 —— 与支付 dispatcher 的 {@link PayCallbackHandler} 路由表完全隔离，<b>退款 key 与支付 key
 * 不撞</b>（同 business_type 在两个 dispatcher 各自命中各自的 handler）。</p>
 *
 * <p><b>fail-fast 校验</b>（AC 4）：同一 business_type 注册多个退款 handler → 启动期抛
 * {@link IllegalStateException}（路由歧义不容忍）。注入 {@code List} 自行 index + 校验，把重复检测
 * 纳入启动 fail-fast（与支付 dispatcher 同款）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-PAY-103)
 */
@Slf4j
@Component
public class RefundCallbackDispatcher {

    /** 退款路由表：business_type → 退款 handler（启动期 index，运行期只读） */
    private final Map<String, IRefundCallbackHandler> routeTable = new HashMap<>();

    /** Spring 注入容器内所有 IRefundCallbackHandler Bean（无实现时为空 List，不报错） */
    private final List<IRefundCallbackHandler> handlers;

    public RefundCallbackDispatcher(List<IRefundCallbackHandler> handlers) {
        this.handlers = handlers;
    }

    /**
     * 启动期建退款路由表 + fail-fast 校验（AC 4）。
     *
     * @throws IllegalStateException 同一 business_type 注册多个退款 handler（路由歧义）
     */
    @PostConstruct
    public void validate() {
        for (IRefundCallbackHandler handler : handlers) {
            String businessType = handler.supportedBusinessType();
            IRefundCallbackHandler existing = routeTable.put(businessType, handler);
            if (existing != null) {
                throw new IllegalStateException(String.format(
                    "退款回调 SPI 路由冲突：business_type='%s' 注册了多个 handler（%s 与 %s），路由歧义，启动中止",
                    businessType, existing.getClass().getName(), handler.getClass().getName()));
            }
        }
        log.info("[gz-pay] 退款回调 SPI 路由表就绪，已注册 {} 个 handler：{}", routeTable.size(), routeTable.keySet());
    }

    /**
     * 按 business_type 取退款 handler（运行期分发用）。
     *
     * @param businessType 原支付交易行 business_type
     * @return 对应退款 handler；无注册（如 test 单）返回 null（调用方跳过分发，不报错）
     */
    public IRefundCallbackHandler resolve(String businessType) {
        return routeTable.get(businessType);
    }

    /**
     * 分发退款成功回调到对应业务退款 handler（doc/10 §6.N10）。
     *
     * <p>由退款回调处理在退款单 refunding→refunded 成功后、callback_log processed 前调用（同事务 REQUIRED）。
     * 无 handler（test 单）→ 跳过不报错。handler 抛异常 → 透传 → 整笔退款回调事务回滚。</p>
     *
     * @param refund 已置 refunded 的退款单
     * @param txn    原支付交易行（已置 refunded）
     */
    public void dispatch(GzPayRefund refund, GzPayTransaction txn) {
        IRefundCallbackHandler handler = resolve(txn.getBusinessType());
        if (handler == null) {
            log.debug("[gz-pay] 无退款 SPI handler 跳过分发 business_type={} refund_no={}",
                txn.getBusinessType(), refund.getRefundNo());
            return;
        }
        log.info("[gz-pay] 退款 SPI 分发 business_type={} → {} refund_no={} out_trade_no={}",
            txn.getBusinessType(), handler.getClass().getSimpleName(), refund.getRefundNo(), refund.getOutTradeNo());
        handler.onRefunded(refund, txn);
    }
}
