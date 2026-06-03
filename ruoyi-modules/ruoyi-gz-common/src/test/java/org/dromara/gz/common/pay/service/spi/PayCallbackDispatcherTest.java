package org.dromara.gz.common.pay.service.spi;

import org.dromara.gz.common.pay.domain.entity.GzPayTransaction;
import org.dromara.gz.common.pay.enums.PayBusinessType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link PayCallbackDispatcher} 单测（GZ-PAY-101 AC 4 / AC 9）。
 *
 * <p>覆盖 SPI 注册中心三大约束：</p>
 * <ol>
 *   <li>fail-fast — 同 business_type 注册 2 个 handler → validate() 抛 IllegalStateException</li>
 *   <li>路由命中 — preorder 回调只触发 preorder handler，gacha handler 0 次</li>
 *   <li>无 handler 跳过 — test 单（无注册）dispatch 不报错、不命中任何 handler</li>
 * </ol>
 *
 * @author kevin-coder (sensenran-guzi · GZ-PAY-101)
 */
@Tag("dev")
class PayCallbackDispatcherTest {

    /** 可计数的 mock handler（记录 onPaid 命中次数 + 收到的交易行） */
    private static class CountingHandler implements PayCallbackHandler {
        private final String businessType;
        private final AtomicInteger count = new AtomicInteger();
        private GzPayTransaction lastTxn;

        CountingHandler(String businessType) {
            this.businessType = businessType;
        }

        @Override
        public String supportedBusinessType() {
            return businessType;
        }

        @Override
        public void onPaid(GzPayTransaction txn) {
            count.incrementAndGet();
            lastTxn = txn;
        }
    }

    private static GzPayTransaction txnOf(String businessType, String outTradeNo) {
        GzPayTransaction tx = new GzPayTransaction();
        tx.setBusinessType(businessType);
        tx.setOutTradeNo(outTradeNo);
        return tx;
    }

    // ============================================================
    //  AC 9 #5：fail-fast —— 同 business_type 注册 2 个 handler → 启动抛异常
    // ============================================================

    @Test
    @DisplayName("AC 4 fail-fast：同 business_type 两个 handler → validate() 抛 IllegalStateException")
    void validate_duplicateBusinessType_failFast() {
        CountingHandler h1 = new CountingHandler(PayBusinessType.PREORDER);
        CountingHandler h2 = new CountingHandler(PayBusinessType.PREORDER);  // 重复 business_type
        PayCallbackDispatcher dispatcher = new PayCallbackDispatcher(List.of(h1, h2));

        IllegalStateException ex = assertThrows(IllegalStateException.class, dispatcher::validate);
        assertTrue(ex.getMessage().contains("路由冲突"));
        assertTrue(ex.getMessage().contains("preorder"));
    }

    @Test
    @DisplayName("AC 4：不同 business_type handler → validate() 正常建表")
    void validate_distinctBusinessType_ok() {
        CountingHandler preorder = new CountingHandler(PayBusinessType.PREORDER);
        CountingHandler gacha = new CountingHandler(PayBusinessType.GACHA);
        PayCallbackDispatcher dispatcher = new PayCallbackDispatcher(List.of(preorder, gacha));

        assertDoesNotThrow(dispatcher::validate);
        assertSame(preorder, dispatcher.resolve(PayBusinessType.PREORDER));
        assertSame(gacha, dispatcher.resolve(PayBusinessType.GACHA));
    }

    // ============================================================
    //  AC 9 #2：路由命中 —— preorder 回调只触发 preorder handler
    // ============================================================

    @Test
    @DisplayName("AC 5 路由：preorder 回调 → 只命中 preorder handler，gacha handler 0 次")
    void dispatch_routesToCorrectHandler() {
        CountingHandler preorder = new CountingHandler(PayBusinessType.PREORDER);
        CountingHandler gacha = new CountingHandler(PayBusinessType.GACHA);
        PayCallbackDispatcher dispatcher = new PayCallbackDispatcher(List.of(preorder, gacha));
        dispatcher.validate();

        dispatcher.dispatch(txnOf(PayBusinessType.PREORDER, "PREORD-20260604-000001"));

        assertEquals(1, preorder.count.get(), "preorder handler 命中 1 次");
        assertEquals(0, gacha.count.get(), "gacha handler 不应命中");
        assertNotNull(preorder.lastTxn);
        assertEquals("PREORD-20260604-000001", preorder.lastTxn.getOutTradeNo());
    }

    // ============================================================
    //  AC 5：test 单（无 handler）→ dispatch 跳过不报错
    // ============================================================

    @Test
    @DisplayName("AC 5：test 单无注册 handler → dispatch 跳过、不报错、不命中任何 handler")
    void dispatch_noHandler_skipSilently() {
        CountingHandler preorder = new CountingHandler(PayBusinessType.PREORDER);
        PayCallbackDispatcher dispatcher = new PayCallbackDispatcher(List.of(preorder));
        dispatcher.validate();

        assertDoesNotThrow(() -> dispatcher.dispatch(txnOf(PayBusinessType.TEST, "TEST-20260604-000001")));
        assertEquals(0, preorder.count.get());
        assertNull(dispatcher.resolve(PayBusinessType.TEST));
    }

    @Test
    @DisplayName("AC 5：handler 抛异常 → dispatch 向上透传（供 handlePaymentNotify 事务回滚）")
    void dispatch_handlerThrows_propagates() {
        PayCallbackHandler throwing = new PayCallbackHandler() {
            @Override
            public String supportedBusinessType() {
                return PayBusinessType.PREORDER;
            }

            @Override
            public void onPaid(GzPayTransaction txn) {
                throw new RuntimeException("业务出单失败");
            }
        };
        PayCallbackDispatcher dispatcher = new PayCallbackDispatcher(List.of(throwing));
        dispatcher.validate();

        RuntimeException ex = assertThrows(RuntimeException.class,
            () -> dispatcher.dispatch(txnOf(PayBusinessType.PREORDER, "PREORD-20260604-000002")));
        assertTrue(ex.getMessage().contains("出单失败"));
    }

    @Test
    @DisplayName("AC 4：空 handler 列表（无任何业务注册）→ validate() 正常，dispatch 任意类型不报错")
    void validate_emptyHandlers_ok() {
        PayCallbackDispatcher dispatcher = new PayCallbackDispatcher(List.of());
        assertDoesNotThrow(dispatcher::validate);
        assertDoesNotThrow(() -> dispatcher.dispatch(txnOf(PayBusinessType.GACHA, "GACHA-20260604-000001")));
    }
}
