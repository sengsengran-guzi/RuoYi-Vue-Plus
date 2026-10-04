package org.dromara.gz.common.pay.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wechat.pay.java.core.Config;
import com.wechat.pay.java.core.auth.Credential;
import com.wechat.pay.java.core.auth.Validator;
import com.wechat.pay.java.core.cipher.SignatureResult;
import com.wechat.pay.java.core.cipher.Signer;
import com.wechat.pay.java.core.http.HttpClient;
import com.wechat.pay.java.core.http.HttpRequest;
import com.wechat.pay.java.core.http.HttpResponse;
import com.wechat.pay.java.core.http.JsonRequestBody;
import com.wechat.pay.java.core.http.OriginalResponse;
import com.wechat.pay.java.service.payments.jsapi.JsapiServiceExtension;
import org.dromara.gz.common.pay.config.WechatPayProperties;
import org.dromara.gz.common.pay.domain.bo.CreateOrderBo;
import org.dromara.gz.common.pay.domain.bo.GzPayTestCreateBo;
import org.dromara.gz.common.pay.domain.entity.GzPayTransaction;
import org.dromara.gz.common.pay.mapper.GzPayCallbackLogMapper;
import org.dromara.gz.common.pay.mapper.GzPayChannelMapper;
import org.dromara.gz.common.pay.mapper.GzPayTransactionMapper;
import org.dromara.gz.common.pay.service.impl.GzPayTransactionServiceImpl;
import org.dromara.gz.common.pay.service.internal.IWechatPayClient;
import org.dromara.gz.common.pay.service.internal.IWechatPayClient.UnifiedOrderRequest;
import org.dromara.gz.common.pay.service.internal.MockWechatPayClient;
import org.dromara.gz.common.pay.service.internal.PayAppidResolver;
import org.dromara.gz.common.pay.service.internal.PayOrderNoGenerator;
import org.dromara.gz.common.pay.service.internal.WechatPayV3ClientImpl;
import org.dromara.gz.common.pay.service.spi.PayCallbackDispatcher;
import org.dromara.gz.common.wechat.WxAppResolver;
import org.dromara.gz.common.wechat.WxMiniappProperties;
import org.dromara.gz.common.wechat.WxMiniappProperties.MiniappApp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * GZ-PAY-106 统一下单带 {@code time_expire} = 本地 {@code expire_time}（REQ-ORDER-012 / FLOW:F-JP-06.step4）。
 *
 * <p>微信 V3 官方约定（JSAPI 下单 {@code time_expire}）：RFC3339 {@code yyyy-MM-DDTHH:mm:ss+TIMEZONE}，
 * 例 {@code 2015-05-20T13:29:35+08:00}；不早于下单后 1 分钟、不晚于 15 天。</p>
 *
 * <ol>
 *   <li><b>mock 通道</b>：service 建单 → mock 记录的下单请求里 timeExpire == 落库 expire_time（业务单 / 测试单两条入口）</li>
 *   <li><b>real 通道</b>：用 SDK 真实 {@link JsapiServiceExtension} + 抓包 {@link HttpClient}，断言<b>发往微信的 JSON 请求体</b>
 *       里 {@code time_expire} 存在、格式合规、与落库 expire_time 同一时刻 —— 只在 record 里加字段而 real 实现不写进
 *       请求体，这里必红</li>
 *   <li>旧构造方式（不带时间）不传 {@code time_expire}（既有调用方行为不变）</li>
 * </ol>
 *
 * <p>不触网、不依赖 DB / Spring 上下文。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-PAY-106)
 */
@Tag("dev")
class PayTimeExpireTest {

    private static final String CLIENT_ID = "mp-applet-sensenran-guzi";
    private static final String APPID = "wx2f8b93e09703f07d";
    private static final String RFC3339_SECONDS = "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}[+-]\\d{2}:\\d{2}$";

    private final ObjectMapper json = new ObjectMapper();

    private WechatPayProperties payProperties;
    private GzPayTransactionMapper transactionMapper;
    private PayOrderNoGenerator orderNoGenerator;
    private PayAppidResolver appidResolver;
    private ArgumentCaptor<GzPayTransaction> insertCap;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        payProperties = new WechatPayProperties();
        payProperties.setClientMode("mock");
        payProperties.setMchId("1731037015");
        payProperties.setNotifyUrl("https://guzi.example.com/api/pay/v3/notify");
        payProperties.getTest().setAmountCent(1L);

        WxMiniappProperties miniapp = new WxMiniappProperties();
        MiniappApp app = new MiniappApp();
        app.setAppid(APPID);
        miniapp.getApps().put(CLIENT_ID, app);
        miniapp.setDefaultClientId(CLIENT_ID);
        appidResolver = new PayAppidResolver(new WxAppResolver(miniapp), mock(GzPayChannelMapper.class), payProperties);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("clientid", CLIENT_ID);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        transactionMapper = mock(GzPayTransactionMapper.class);
        orderNoGenerator = mock(PayOrderNoGenerator.class);
        when(orderNoGenerator.generate(anyString())).thenReturn("PINDOU-20261005-000001");
        insertCap = ArgumentCaptor.forClass(GzPayTransaction.class);
        doAnswer(inv -> {
            GzPayTransaction tx = inv.getArgument(0);
            tx.setId(7001L);
            return 1;
        }).when(transactionMapper).insert(insertCap.capture());
        when(transactionMapper.markPending(anyLong(), anyString())).thenReturn(1);
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    private GzPayTransactionServiceImpl newService(IWechatPayClient client) {
        @SuppressWarnings("unchecked")
        ObjectProvider<PayCallbackDispatcher> dispatcherProvider = mock(ObjectProvider.class);
        when(dispatcherProvider.getObject()).thenReturn(mock(PayCallbackDispatcher.class));
        return new GzPayTransactionServiceImpl(transactionMapper, mock(GzPayCallbackLogMapper.class), orderNoGenerator,
            client, payProperties, mock(IGzPayShippingService.class), dispatcherProvider, appidResolver);
    }

    private static CreateOrderBo pindouOrder() {
        return CreateOrderBo.builder()
            .businessType("pindou")
            .businessOrderNo("BK20261005-000001")
            .amountCent(3000L)
            .openid("openid_guzi_001")
            .userId(11L)
            .description("拼豆座位")
            .build();
    }

    /** 落库 expire_time 在本 JVM 时区下的绝对时刻。 */
    private static OffsetDateTime expireOf(GzPayTransaction tx) {
        return tx.getExpireTime().atZone(ZoneId.systemDefault()).toOffsetDateTime();
    }

    // ============================================================
    //  mock 通道
    // ============================================================

    @Test
    @DisplayName("mock 通道：业务建单 → 下单请求 timeExpire == 落库 expire_time（秒精度、约 now+5min）")
    void mockChannel_businessOrder_timeExpireEqualsLocalExpireTime() {
        MockWechatPayClient mockClient = new MockWechatPayClient(payProperties);
        LocalDateTime before = LocalDateTime.now();

        newService(mockClient).createBusinessOrder(pindouOrder());

        GzPayTransaction inserted = insertCap.getValue();
        UnifiedOrderRequest req = mockClient.getLastUnifiedOrderRequest();
        assertNotNull(req, "mock 应记录下单请求");
        assertNotNull(req.timeExpire(), "统一下单必须带 time_expire");
        assertNotNull(inserted.getExpireTime());
        assertEquals(inserted.getExpireTime(), req.timeExpire().toLocalDateTime(), "time_expire 本地时间 == expire_time");
        assertEquals(expireOf(inserted).getOffset(), req.timeExpire().getOffset(), "时区偏移 == JVM 时区");
        assertEquals(0, inserted.getExpireTime().getNano(), "expire_time 秒精度，与微信 time_expire 逐字相等");
        assertTrue(req.timeExpireRfc3339().matches(RFC3339_SECONDS), "RFC3339 带时区：" + req.timeExpireRfc3339());

        long secondsAhead = ChronoUnit.SECONDS.between(before, inserted.getExpireTime());
        assertTrue(secondsAhead >= 4 * 60 && secondsAhead <= 5 * 60,
            "5 分钟有效期（≥ 微信下限 1 分钟）：实际 " + secondsAhead + "s");
    }

    @Test
    @DisplayName("mock 通道：测试单入口（createTestOrder）同样带 time_expire")
    void mockChannel_testOrder_alsoCarriesTimeExpire() {
        MockWechatPayClient mockClient = new MockWechatPayClient(payProperties);
        GzPayTestCreateBo bo = new GzPayTestCreateBo();
        bo.setAmountCent(1L);
        bo.setOpenid("openid_admin");

        newService(mockClient).createTestOrder(bo, 1L);

        UnifiedOrderRequest req = mockClient.getLastUnifiedOrderRequest();
        assertNotNull(req.timeExpire());
        assertEquals(insertCap.getValue().getExpireTime(), req.timeExpire().toLocalDateTime());
    }

    // ============================================================
    //  real 通道：抓发往微信的请求体
    // ============================================================

    @Test
    @DisplayName("real 通道：service 建单 → 发往 /v3/pay/transactions/jsapi 的 JSON 请求体含 time_expire == 落库 expire_time")
    void realChannel_requestBodyCarriesTimeExpire() throws Exception {
        givenMerchantPrivateKey();
        CapturingHttpClient http = new CapturingHttpClient();
        WechatPayV3ClientImpl realClient = realClientWith(http);

        newService(realClient).createBusinessOrder(pindouOrder());

        HttpRequest sent = http.single();
        assertTrue(sent.getUrl().toString().endsWith("/v3/pay/transactions/jsapi"), "下单接口：" + sent.getUrl());
        JsonNode body = json.readTree(((JsonRequestBody) sent.getBody()).getBody());
        assertEquals("PINDOU-20261005-000001", body.path("out_trade_no").asText());
        assertTrue(body.hasNonNull("time_expire"), "请求体必须含 time_expire：" + body);

        String timeExpire = body.get("time_expire").asText();
        assertTrue(timeExpire.matches(RFC3339_SECONDS), "RFC3339 yyyy-MM-ddTHH:mm:ss+TIMEZONE：" + timeExpire);
        OffsetDateTime parsed = OffsetDateTime.parse(timeExpire);
        OffsetDateTime local = expireOf(insertCap.getValue());
        assertEquals(local.toInstant(), parsed.toInstant(), "微信侧失效时刻 == 本地 expire_time");
        assertEquals(local.getOffset(), parsed.getOffset());
    }

    @Test
    @DisplayName("real 通道：time_expire 按微信格式输出（+08:00 / 截到秒 / UTC 写 +00:00 而非 Z）")
    void realChannel_formatsRfc3339() throws Exception {
        CapturingHttpClient http = new CapturingHttpClient();
        WechatPayV3ClientImpl realClient = realClientWith(http);

        OffsetDateTime cst = OffsetDateTime.of(2026, 10, 5, 12, 5, 0, 987_000_000, ZoneOffset.ofHours(8));
        realClient.createJsapiOrder(new UnifiedOrderRequest("PINDOU-20261005-000002", 3000L, "openid", "拼豆",
            APPID, cst));
        assertEquals("2026-10-05T12:05:00+08:00", json.readTree(http.lastBody()).get("time_expire").asText());

        OffsetDateTime utc = OffsetDateTime.of(2026, 10, 5, 4, 5, 0, 0, ZoneOffset.UTC);
        realClient.createJsapiOrder(new UnifiedOrderRequest("PINDOU-20261005-000003", 3000L, "openid", "拼豆",
            APPID, utc));
        assertEquals("2026-10-05T04:05:00+00:00", json.readTree(http.lastBody()).get("time_expire").asText());
    }

    @Test
    @DisplayName("旧构造方式（5 参，不带时间）→ timeExpire 为空，请求体不出现 time_expire（既有调用方行为不变）")
    void legacyConstructor_omitsTimeExpire() throws Exception {
        UnifiedOrderRequest legacy = new UnifiedOrderRequest("PINDOU-20261005-000004", 3000L, "openid", "拼豆", APPID);
        assertNull(legacy.timeExpire());
        assertNull(legacy.timeExpireRfc3339());

        CapturingHttpClient http = new CapturingHttpClient();
        realClientWith(http).createJsapiOrder(legacy);
        assertFalse(json.readTree(http.lastBody()).has("time_expire"), "旧调用方式不应凭空多出 time_expire");
    }

    // ============================================================
    //  fixtures
    // ============================================================

    /** real 客户端 + SDK 真实 JsapiServiceExtension（签名器 mock、HTTP 层抓包，不触网）。 */
    private WechatPayV3ClientImpl realClientWith(CapturingHttpClient http) throws Exception {
        Signer signer = mock(Signer.class);
        when(signer.sign(anyString())).thenReturn(new SignatureResult("test_sign", "test_serial"));
        when(signer.getAlgorithm()).thenReturn("SHA256-RSA2048");
        Config config = mock(Config.class);
        when(config.createSigner()).thenReturn(signer);
        // SDK 先按 config 建默认 HttpClient（要求凭证 / 验签器非空），随后被下面的抓包 httpClient 覆盖
        when(config.createCredential()).thenReturn(mock(Credential.class));
        when(config.createValidator()).thenReturn(mock(Validator.class));
        JsapiServiceExtension jsapi = new JsapiServiceExtension.Builder()
            .config(config).httpClient(http).signType("RSA").build();

        WechatPayV3ClientImpl client = new WechatPayV3ClientImpl(payProperties);
        java.lang.reflect.Field f = WechatPayV3ClientImpl.class.getDeclaredField("jsapiService");
        f.setAccessible(true);
        f.set(client, jsapi);
        return client;
    }

    /** service 建单后会走 real buildPayParams 用商户私钥签名 → 生成一把临时私钥。 */
    private void givenMerchantPrivateKey() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        KeyPair kp = gen.generateKeyPair();
        String pem = "-----BEGIN PRIVATE KEY-----\n"
            + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8)).encodeToString(kp.getPrivate().getEncoded())
            + "\n-----END PRIVATE KEY-----\n";
        Path keyFile = tempDir.resolve("merchant-private-key.pem");
        Files.writeString(keyFile, pem, StandardCharsets.UTF_8);
        payProperties.setPrivateKeyPath(keyFile.toString());
    }

    /** 抓包 HttpClient：记录 SDK 实际发出的请求，回一个固定 prepay_id。 */
    private static final class CapturingHttpClient implements HttpClient {

        private final List<HttpRequest> requests = new ArrayList<>();

        @Override
        public <T> HttpResponse<T> execute(HttpRequest request, Class<T> responseClass) {
            requests.add(request);
            OriginalResponse original = new OriginalResponse.Builder()
                .request(request)
                .statusCode(200)
                .headers(Map.of())
                .contentType("application/json")
                .body("{\"prepay_id\":\"wx_prepay_captured\"}")
                .build();
            return new HttpResponse.Builder<T>().originalResponse(original).serviceResponseType(responseClass).build();
        }

        @Override
        public InputStream download(String url) {
            throw new UnsupportedOperationException("测试抓包客户端不支持下载");
        }

        HttpRequest single() {
            assertEquals(1, requests.size(), "应恰好发出 1 个请求");
            return requests.get(0);
        }

        String lastBody() {
            return ((JsonRequestBody) requests.get(requests.size() - 1).getBody()).getBody();
        }
    }
}
