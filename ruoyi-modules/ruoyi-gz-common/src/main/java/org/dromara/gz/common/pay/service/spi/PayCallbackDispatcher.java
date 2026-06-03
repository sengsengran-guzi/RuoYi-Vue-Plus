package org.dromara.gz.common.pay.service.spi;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.dromara.gz.common.pay.domain.entity.GzPayTransaction;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 支付回调业务分发注册中心（GZ-PAY-101 AC 4，决策 D2）。
 *
 * <p>Spring 启动时把所有 {@link PayCallbackHandler} Bean 按 {@code supportedBusinessType()}
 * 收成 {@code Map<String, PayCallbackHandler>}（路由表）。回调成功后按交易行 business_type 取
 * handler 调 {@code onPaid} —— <b>无 switch-case，新业务接入零改动本类</b>（AC 4 / 强约束 #4）。</p>
 *
 * <p><b>fail-fast 校验</b>（AC 4）：同一 business_type 注册多个 handler → 启动期
 * {@link #validate()} 抛 {@link IllegalStateException}（路由表歧义，绝不容忍运行期随机命中）。</p>
 *
 * <p><b>构造注入 List 而非 Map</b>：Spring 直接注入 {@code Map<String, Bean>} 的 key 是 <b>Bean
 * 名</b>而非 {@code supportedBusinessType()}，且无法在装配阶段检测同业务类型重复 —— 故注入
 * {@code List<PayCallbackHandler>} 自行 index + 校验，把重复检测纳入启动 fail-fast。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-PAY-101)
 */
@Slf4j
@Component
public class PayCallbackDispatcher {

    /** 路由表：business_type → handler（启动期 index，运行期只读） */
    private final Map<String, PayCallbackHandler> routeTable = new HashMap<>();

    /** Spring 注入容器内所有 PayCallbackHandler Bean（无任何实现时为空 List，不报错） */
    private final List<PayCallbackHandler> handlers;

    public PayCallbackDispatcher(List<PayCallbackHandler> handlers) {
        this.handlers = handlers;
    }

    /**
     * 启动期建路由表 + fail-fast 校验（AC 4）。
     *
     * @throws IllegalStateException 同一 business_type 注册多个 handler（路由歧义）
     */
    @PostConstruct
    public void validate() {
        for (PayCallbackHandler handler : handlers) {
            String businessType = handler.supportedBusinessType();
            PayCallbackHandler existing = routeTable.put(businessType, handler);
            if (existing != null) {
                throw new IllegalStateException(String.format(
                    "支付回调 SPI 路由冲突：business_type='%s' 注册了多个 handler（%s 与 %s），路由歧义，启动中止",
                    businessType, existing.getClass().getName(), handler.getClass().getName()));
            }
        }
        log.info("[gz-pay] 支付回调 SPI 路由表就绪，已注册 {} 个 handler：{}", routeTable.size(), routeTable.keySet());
    }

    /**
     * 按 business_type 取 handler（运行期分发用）。
     *
     * @param businessType 交易行 business_type
     * @return 对应 handler；无注册（如 test 单）返回 null（调用方跳过分发，不报错）
     */
    public PayCallbackHandler resolve(String businessType) {
        return routeTable.get(businessType);
    }

    /**
     * 分发支付成功回调到对应业务 handler（doc/10 §6.N6）。
     *
     * <p>由 {@code handlePaymentNotify} 在交易行 pending→paid 成功后、callback_log processed 前调用
     * （同事务 REQUIRED）。无 handler（test 单）→ 跳过不报错，保持 PAY-001 test 单行为（AC 5）。
     * handler 抛异常 → 透传给调用方 → 整笔回调事务回滚（AC 5 / doc/10 §6.E2）。</p>
     *
     * @param txn 已置 paid 的支付交易行
     */
    public void dispatch(GzPayTransaction txn) {
        PayCallbackHandler handler = resolve(txn.getBusinessType());
        if (handler == null) {
            // test 单或暂未注册 handler 的业务类型 → 跳过分发（不报错，AC 5）
            log.debug("[gz-pay] 无 SPI handler 跳过分发 business_type={} out_trade_no={}",
                txn.getBusinessType(), txn.getOutTradeNo());
            return;
        }
        log.info("[gz-pay] SPI 分发支付回调 business_type={} → {} out_trade_no={} business_order_no={}",
            txn.getBusinessType(), handler.getClass().getSimpleName(), txn.getOutTradeNo(), txn.getBusinessOrderNo());
        // 抛异常向上透传 → handlePaymentNotify 事务回滚（doc/10 §6.E2）
        handler.onPaid(txn);
    }
}
