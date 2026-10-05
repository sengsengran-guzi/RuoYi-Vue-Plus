package org.dromara.gz.common.pay.service;

import com.wechat.pay.java.core.Config;
import com.wechat.pay.java.core.auth.Credential;
import com.wechat.pay.java.core.auth.Validator;
import com.wechat.pay.java.core.cipher.SignatureResult;
import com.wechat.pay.java.core.cipher.Signer;
import com.wechat.pay.java.core.http.HttpClient;
import com.wechat.pay.java.core.http.HttpMethod;
import com.wechat.pay.java.core.http.HttpRequest;
import com.wechat.pay.java.core.http.HttpResponse;
import com.wechat.pay.java.core.http.OriginalResponse;
import com.wechat.pay.java.service.refund.RefundService;
import org.dromara.gz.common.pay.config.WechatPayProperties;
import org.dromara.gz.common.pay.service.internal.IWechatPayClient;
import org.dromara.gz.common.pay.service.internal.IWechatPayClient.RefundQueryResult;
import org.dromara.gz.common.pay.service.internal.IWechatPayClient.RefundRequest;
import org.dromara.gz.common.pay.service.internal.IWechatPayClient.RefundResult;
import org.dromara.gz.common.pay.service.internal.MockWechatPayClient;
import org.dromara.gz.common.pay.service.internal.WechatPayV3ClientImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * GZ-JP-111 通道层「按商户退款单号查单」（{@link IWechatPayClient#queryRefund}）—— mock 与 real 两个实现。
 *
 * <ol>
 *   <li><b>mock</b>：按 mock 退款登记表回答 —— 受理过的单 PROCESSING、注入后 SUCCESS / ABNORMAL / CLOSED、
 *       没受理过的 NOT_FOUND；同号再受理不把已成功的单倒退回 PROCESSING；查询失败开关真抛异常。</li>
 *   <li><b>real</b>：SDK 真实 {@link RefundService} + 抓包 {@link HttpClient}（不触网）—— 断言发出的是
 *       {@code GET /v3/refund/domestic/refunds/{out_refund_no}}，响应体映射成 status / refundId / successTime；
 *       微信 404 {@code RESOURCE_NOT_EXISTS} 归一成 NOT_FOUND（不抛）；其余错误照常抛（不能被当成 NOT_FOUND 去补发）。</li>
 *   <li><b>接口只新增</b>：既有 {@code refund(RefundRequest)} 等签名原样在。</li>
 * </ol>
 *
 * @author kevin-coder (sensenran-guzi · GZ-JP-111)
 */
@Tag("dev")
@DisplayName("GZ-JP-111 通道层按退款单号查单（mock + real）")
class WechatPayQueryRefundTest {

    private static final String REFUND_NO = "JPRF-20261005-000001";

    // ============================================================
    //  mock 实现
    // ============================================================

    private static MockWechatPayClient mockClient() {
        return new MockWechatPayClient(new WechatPayProperties());
    }

    private static RefundRequest refundReq(String refundNo) {
        return new RefundRequest("JPO-20261005-000001", refundNo, 3000L, 9000L, "购买失败", "");
    }

    @Test
    @DisplayName("mock：从未受理过的退款单 → NOT_FOUND（refundId 为空），不抛异常")
    void mock_unknownRefund_notFound() {
        RefundQueryResult q = mockClient().queryRefund(REFUND_NO);
        assertNotNull(q);
        assertEquals(RefundQueryResult.NOT_FOUND, q.status());
        assertEquals(REFUND_NO, q.outRefundNo());
        assertNull(q.refundId());
        assertNull(q.successTime());
    }

    @Test
    @DisplayName("mock：refund 受理成功即登记 → 查单 PROCESSING，refundId 与受理返回一致")
    void mock_acceptedRefund_processing() {
        MockWechatPayClient client = mockClient();
        RefundResult accepted = client.refund(refundReq(REFUND_NO));

        RefundQueryResult q = client.queryRefund(REFUND_NO);
        assertEquals(RefundQueryResult.PROCESSING, q.status());
        assertEquals(accepted.refundId(), q.refundId());
        assertNull(q.successTime(), "未成功不该有成功时间");
        assertTrue(q.rawBody().contains(REFUND_NO));
    }

    @Test
    @DisplayName("mock：受理失败的单不登记 → 查单仍 NOT_FOUND")
    void mock_acceptFailed_notRegistered() {
        MockWechatPayClient client = mockClient();
        client.setRefundAcceptFail(true);
        assertThrows(RuntimeException.class, () -> client.refund(refundReq(REFUND_NO)));
        assertEquals(RefundQueryResult.NOT_FOUND, client.queryRefund(REFUND_NO).status());
    }

    @Test
    @DisplayName("mock：注入 SUCCESS → 返回 SUCCESS + refundId + successTime")
    void mock_success() {
        MockWechatPayClient client = mockClient();
        client.refund(refundReq(REFUND_NO));
        client.setQueryRefundStatus(REFUND_NO, RefundQueryResult.SUCCESS);

        RefundQueryResult q = client.queryRefund(REFUND_NO);
        assertEquals(RefundQueryResult.SUCCESS, q.status());
        assertEquals("mock_refund_id_" + REFUND_NO, q.refundId());
        assertNotNull(q.successTime(), "SUCCESS 必须带成功时间");
        assertTrue(q.rawBody().contains("\"status\":\"SUCCESS\""), q.rawBody());
    }

    @ParameterizedTest(name = "mock：注入 {0} → 原样返回")
    @ValueSource(strings = {"ABNORMAL", "CLOSED", "PROCESSING"})
    void mock_terminalOrProcessing(String status) {
        MockWechatPayClient client = mockClient();
        client.setQueryRefundStatus(REFUND_NO, status);
        RefundQueryResult q = client.queryRefund(REFUND_NO);
        assertEquals(status, q.status());
        assertEquals("mock_refund_id_" + REFUND_NO, q.refundId());
        assertNull(q.successTime());
    }

    @Test
    @DisplayName("mock：同号再受理（微信按 out_refund_no 幂等）→ 已 SUCCESS 的单不倒退回 PROCESSING")
    void mock_resendSameNo_keepsSuccess() {
        MockWechatPayClient client = mockClient();
        client.refund(refundReq(REFUND_NO));
        client.setQueryRefundStatus(REFUND_NO, RefundQueryResult.SUCCESS);
        client.refund(refundReq(REFUND_NO));
        assertEquals(RefundQueryResult.SUCCESS, client.queryRefund(REFUND_NO).status());
    }

    @Test
    @DisplayName("mock：注入 NOT_FOUND = 从登记表移除；查询失败开关 → 抛异常而不是返回 NOT_FOUND")
    void mock_removeAndFail() {
        MockWechatPayClient client = mockClient();
        client.refund(refundReq(REFUND_NO));
        client.setQueryRefundStatus(REFUND_NO, RefundQueryResult.NOT_FOUND);
        assertEquals(RefundQueryResult.NOT_FOUND, client.queryRefund(REFUND_NO).status());

        client.setQueryRefundFail(true);
        assertThrows(RuntimeException.class, () -> client.queryRefund(REFUND_NO));
    }

    // ============================================================
    //  real 实现（SDK 真实 RefundService + 抓包 HttpClient）
    // ============================================================

    @Test
    @DisplayName("real：GET /v3/refund/domestic/refunds/{out_refund_no}，SUCCESS 映射 status / refundId / successTime")
    void real_success() throws Exception {
        StubHttpClient http = StubHttpClient.ok(refundJson("SUCCESS", "\"success_time\":\"2026-10-05T12:05:00+08:00\","));
        RefundQueryResult q = realClientWith(http).queryRefund(REFUND_NO);

        HttpRequest sent = http.single();
        assertEquals(HttpMethod.GET, sent.getHttpMethod());
        assertTrue(sent.getUrl().toString().endsWith("/v3/refund/domestic/refunds/" + REFUND_NO),
            "查单接口：" + sent.getUrl());

        assertEquals(RefundQueryResult.SUCCESS, q.status());
        assertEquals("50300908092021012000000000001", q.refundId());
        assertEquals(REFUND_NO, q.outRefundNo());
        assertEquals("2026-10-05T12:05:00+08:00", q.successTime());
        assertNotNull(q.rawBody());
    }

    @ParameterizedTest(name = "real：微信退款单状态 {0} → 原样映射")
    @ValueSource(strings = {"PROCESSING", "ABNORMAL", "CLOSED"})
    void real_otherStatuses(String status) throws Exception {
        RefundQueryResult q = realClientWith(StubHttpClient.ok(refundJson(status, ""))).queryRefund(REFUND_NO);
        assertEquals(status, q.status());
        assertEquals("50300908092021012000000000001", q.refundId());
        assertNull(q.successTime());
    }

    @Test
    @DisplayName("real：微信 404 RESOURCE_NOT_EXISTS → NOT_FOUND（不抛），rawBody 带微信错误报文")
    void real_notExists_notFound() throws Exception {
        String err = "{\"code\":\"RESOURCE_NOT_EXISTS\",\"message\":\"退款单不存在\"}";
        RefundQueryResult q = realClientWith(StubHttpClient.error(404, err)).queryRefund(REFUND_NO);
        assertEquals(RefundQueryResult.NOT_FOUND, q.status());
        assertEquals(REFUND_NO, q.outRefundNo());
        assertNull(q.refundId());
        assertEquals(err, q.rawBody());
    }

    @Test
    @DisplayName("real：其余错误（500 SYSTEM_ERROR / 无业务码的 404）照常抛出 —— 不能被当成 NOT_FOUND 去补发")
    void real_otherErrors_throw() throws Exception {
        WechatPayV3ClientImpl c1 = realClientWith(
            StubHttpClient.error(500, "{\"code\":\"SYSTEM_ERROR\",\"message\":\"系统错误\"}"));
        assertThrows(com.wechat.pay.java.core.exception.ServiceException.class, () -> c1.queryRefund(REFUND_NO));

        WechatPayV3ClientImpl c2 = realClientWith(StubHttpClient.error(404, ""));
        assertThrows(com.wechat.pay.java.core.exception.ServiceException.class, () -> c2.queryRefund(REFUND_NO));
    }

    // ============================================================
    //  接口只新增、不改既有签名
    // ============================================================

    @Test
    @DisplayName("接口：queryRefund 为新增方法；既有 refund / parseAndVerifyRefundNotify / queryByOutTradeNo 签名原样")
    void interface_onlyAdded() throws Exception {
        Method q = IWechatPayClient.class.getMethod("queryRefund", String.class);
        assertEquals(RefundQueryResult.class, q.getReturnType());
        assertEquals(RefundResult.class, IWechatPayClient.class.getMethod("refund", RefundRequest.class).getReturnType());
        assertEquals(IWechatPayClient.RefundCallbackResult.class, IWechatPayClient.class
            .getMethod("parseAndVerifyRefundNotify", IWechatPayClient.NotifyContext.class).getReturnType());
        assertEquals(IWechatPayClient.QueryResult.class,
            IWechatPayClient.class.getMethod("queryByOutTradeNo", String.class).getReturnType());
        // RefundResult 三个组件原样（拼豆 / 回收在用）
        assertEquals(3, RefundResult.class.getRecordComponents().length);
    }

    // ============================================================
    //  fixtures
    // ============================================================

    private static String refundJson(String status, String extra) {
        return "{\"refund_id\":\"50300908092021012000000000001\",\"out_refund_no\":\"" + REFUND_NO + "\","
            + "\"transaction_id\":\"4200000000202610050000000001\",\"out_trade_no\":\"JPO-20261005-000001\","
            + "\"channel\":\"ORIGINAL\",\"user_received_account\":\"支付用户零钱\","
            + extra
            + "\"create_time\":\"2026-10-05T12:00:00+08:00\",\"status\":\"" + status + "\","
            + "\"funds_account\":\"AVAILABLE\","
            + "\"amount\":{\"total\":9000,\"refund\":3000,\"payer_total\":9000,\"payer_refund\":3000,"
            + "\"settlement_refund\":3000,\"settlement_total\":9000,\"discount_refund\":0,\"currency\":\"CNY\"}}";
    }

    /** real 客户端 + SDK 真实 RefundService（签名器 mock、HTTP 层桩，不触网）。 */
    private static WechatPayV3ClientImpl realClientWith(StubHttpClient http) throws Exception {
        Signer signer = mock(Signer.class);
        when(signer.sign(anyString())).thenReturn(new SignatureResult("test_sign", "test_serial"));
        when(signer.getAlgorithm()).thenReturn("SHA256-RSA2048");
        Config config = mock(Config.class);
        when(config.createSigner()).thenReturn(signer);
        when(config.createCredential()).thenReturn(mock(Credential.class));
        when(config.createValidator()).thenReturn(mock(Validator.class));
        RefundService refundService = new RefundService.Builder().config(config).httpClient(http).build();

        WechatPayProperties props = new WechatPayProperties();
        props.setMchId("1731037015");
        WechatPayV3ClientImpl client = new WechatPayV3ClientImpl(props);
        Field f = WechatPayV3ClientImpl.class.getDeclaredField("refundService");
        f.setAccessible(true);
        f.set(client, refundService);
        return client;
    }

    /**
     * 桩 HttpClient：记录 SDK 发出的请求；成功回固定 JSON，失败照 SDK 默认 HttpClient 的做法抛
     * {@link com.wechat.pay.java.core.exception.ServiceException}（非 2xx → 带状态码与响应体）。
     */
    private static final class StubHttpClient implements HttpClient {

        private final List<HttpRequest> requests = new ArrayList<>();
        private final int status;
        private final String body;

        private StubHttpClient(int status, String body) {
            this.status = status;
            this.body = body;
        }

        static StubHttpClient ok(String body) {
            return new StubHttpClient(200, body);
        }

        static StubHttpClient error(int status, String body) {
            return new StubHttpClient(status, body);
        }

        @Override
        public <T> HttpResponse<T> execute(HttpRequest request, Class<T> responseClass) {
            requests.add(request);
            if (status / 100 != 2) {
                throw new com.wechat.pay.java.core.exception.ServiceException(request, status, body);
            }
            OriginalResponse original = new OriginalResponse.Builder()
                .request(request)
                .statusCode(status)
                .headers(Map.of())
                .contentType("application/json")
                .body(body)
                .build();
            return new HttpResponse.Builder<T>().originalResponse(original).serviceResponseType(responseClass).build();
        }

        @Override
        public InputStream download(String url) {
            throw new UnsupportedOperationException("桩客户端不支持下载");
        }

        HttpRequest single() {
            assertEquals(1, requests.size(), "应恰好发出 1 个请求");
            return requests.get(0);
        }
    }
}
