package org.dromara.gz.common.pay.service;

import com.wechat.pay.java.service.payments.jsapi.JsapiServiceExtension;
import com.wechat.pay.java.service.payments.jsapi.model.PrepayRequest;
import com.wechat.pay.java.service.payments.jsapi.model.PrepayWithRequestPaymentResponse;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.gz.common.pay.config.WechatPayProperties;
import org.dromara.gz.common.pay.domain.bo.CreateOrderBo;
import org.dromara.gz.common.pay.domain.entity.GzPayChannel;
import org.dromara.gz.common.pay.domain.entity.GzPayTransaction;
import org.dromara.gz.common.pay.domain.vo.MpPayParamsVO;
import org.dromara.gz.common.pay.enums.PayBusinessType;
import org.dromara.gz.common.pay.mapper.GzPayCallbackLogMapper;
import org.dromara.gz.common.pay.mapper.GzPayChannelMapper;
import org.dromara.gz.common.pay.mapper.GzPayTransactionMapper;
import org.dromara.gz.common.pay.service.impl.GzPayTransactionServiceImpl;
import org.dromara.gz.common.pay.service.internal.IWechatPayClient.JsapiPayParams;
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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.Signature;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * GZ-SYS-022 微信支付多 appid —— 统一下单与<b>调起支付签名</b>的 appid 来自下单上下文（ADR-0019 §3）。
 *
 * <p>改造前 {@code WechatPayV3ClientImpl} 的 :93（统一下单）与 :125（5 参签名）都读全局
 * {@code gz.pay.appid}。JSAPI 的 prepay_id 与 appid 绑定、签名第一因子也是 appid，所以新小程序
 * 拿旧 appid 签出来的 paySign 调起<b>必失败</b>。本测试锁死改造后的行为：</p>
 *
 * <ol>
 *   <li><b>§A 真签名验证</b> —— 生成一对真 RSA 密钥当商户私钥，对 {@code buildPayParams} 产出的
 *       paySign 做<b>密码学验签</b>：用「传入的 appid」拼的 message 验得过，用「全局配置里的 appid」
 *       拼的 message 验不过。这是「签名 appid 真的来自下单上下文」最硬的证据 ——
 *       断言两个 String 相等只能证明参数被读了，验签能证明<b>被签进去的字节</b>就是它。</li>
 *   <li><b>§B 统一下单</b> —— 反射注入 mock 的 SDK {@code JsapiServiceExtension}，捕获
 *       {@code PrepayRequest.appid} 断言是上下文 appid（同样：全局配置里放一个不同的值做对照）。</li>
 *   <li><b>§C 解析优先级</b> —— {@code gz_pay_channel.appid} → {@code wx.miniapp.apps.<clientid>.appid}
 *       → {@code gz.pay.appid} 兜底；含「后台 PC clientid 不抛错」这条防回归（GZ-SYS-020 §6 同类事故）。</li>
 *   <li><b>§D 端到端</b> —— 同一个进程内切 clientid，两个小程序各自下单、各自签名。</li>
 * </ol>
 *
 * <p>不触网、不依赖 DB / Spring 上下文。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-SYS-022)
 */
@Tag("dev")
class WechatPayMultiAppidTest {

    private static final String CID_GUZI = "mp-applet-sensenran-guzi";
    private static final String CID_JP = "mp-applet-gz-jp";
    private static final String APPID_GUZI = "wx2f8b93e09703f07d";
    private static final String APPID_JP = "wxjp0000000000jp01";
    /** 全局 gz.pay.appid —— 故意与两个小程序都不同，任何一处「还在读全局」都会被逮到 */
    private static final String APPID_GLOBAL_LEGACY = "wxGLOBALLEGACY001";
    private static final String ADMIN_PC_CLIENT_ID = "e5cd7e4891bf95d1d19206ce24a7b32e";
    private static final String CLIENT_HEADER = "clientid";

    private WechatPayProperties payProperties;
    private WxMiniappProperties miniappProperties;
    private GzPayChannelMapper channelMapper;
    private PayAppidResolver resolver;

    @BeforeEach
    void setUp() {
        payProperties = new WechatPayProperties();
        payProperties.setClientMode("mock");
        payProperties.setAppid(APPID_GLOBAL_LEGACY);
        payProperties.setMchId("1731037015");

        miniappProperties = new WxMiniappProperties();
        MiniappApp guzi = new MiniappApp();
        guzi.setAppid(APPID_GUZI);
        MiniappApp jp = new MiniappApp();
        jp.setAppid(APPID_JP);
        miniappProperties.getApps().put(CID_GUZI, guzi);
        miniappProperties.getApps().put(CID_JP, jp);
        miniappProperties.setDefaultClientId(CID_GUZI);

        channelMapper = mock(GzPayChannelMapper.class);
        resolver = new PayAppidResolver(new WxAppResolver(miniappProperties), channelMapper, payProperties);
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    private void givenRequestWithClientId(String clientId) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (clientId != null) {
            request.addHeader(CLIENT_HEADER, clientId);
        }
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    // ============================================================
    //  §A 调起支付签名（WechatPayV3ClientImpl:125）—— 真 RSA 验签
    // ============================================================

    @TempDir
    Path tempDir;

    /** 生成一对真 RSA 密钥，把私钥按 PKCS8 PEM 写进临时文件当「商户私钥」，返回公钥用于验签。 */
    private PublicKey givenMerchantKeyPair() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        KeyPair kp = gen.generateKeyPair();
        String pem = "-----BEGIN PRIVATE KEY-----\n"
            + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8))
                .encodeToString(kp.getPrivate().getEncoded())
            + "\n-----END PRIVATE KEY-----\n";
        Path keyFile = tempDir.resolve("merchant-private-key.pem");
        Files.writeString(keyFile, pem, StandardCharsets.UTF_8);
        payProperties.setPrivateKeyPath(keyFile.toString());
        return kp.getPublic();
    }

    /** 用公钥验 paySign 是否是对「appid + 5 参 message」的 RSA-SHA256 签名。 */
    private boolean verifyPaySign(PublicKey publicKey, String appid, JsapiPayParams p) throws Exception {
        String message = appid + "\n" + p.timeStamp() + "\n" + p.nonceStr() + "\n" + p.packageVal() + "\n";
        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(publicKey);
        verifier.update(message.getBytes(StandardCharsets.UTF_8));
        return verifier.verify(Base64.getDecoder().decode(p.paySign()));
    }

    @Test
    @DisplayName("§A ★ 调起签名的 appid 来自下单上下文，不是全局 gz.pay.appid（真 RSA 验签）")
    void buildPayParams_signsWithContextAppid_notGlobalConfig() throws Exception {
        PublicKey publicKey = givenMerchantKeyPair();
        WechatPayV3ClientImpl client = new WechatPayV3ClientImpl(payProperties);

        JsapiPayParams p = client.buildPayParams("wx20260806JP0001", APPID_JP);

        assertTrue(verifyPaySign(publicKey, APPID_JP, p),
            "paySign 必须是对「下单上下文 appid」拼出的 message 的签名");
        assertFalse(verifyPaySign(publicKey, APPID_GLOBAL_LEGACY, p),
            "★ 防回归：用全局 gz.pay.appid 拼的 message 必须验不过（验得过 = :125 又读回全局配置了）");
        assertFalse(verifyPaySign(publicKey, APPID_GUZI, p),
            "★ 防串号：另一个小程序的 appid 也必须验不过");
        assertEquals("prepay_id=wx20260806JP0001", p.packageVal());
        assertEquals("RSA", p.signType());
    }

    @Test
    @DisplayName("§A 同一个 prepay_id，两个小程序签出的 paySign 各自只对自己的 appid 成立")
    void buildPayParams_twoMiniapps_eachSignsItsOwnAppid() throws Exception {
        PublicKey publicKey = givenMerchantKeyPair();
        WechatPayV3ClientImpl client = new WechatPayV3ClientImpl(payProperties);

        JsapiPayParams guziParams = client.buildPayParams("wx_same_prepay_id", APPID_GUZI);
        JsapiPayParams jpParams = client.buildPayParams("wx_same_prepay_id", APPID_JP);

        assertTrue(verifyPaySign(publicKey, APPID_GUZI, guziParams));
        assertTrue(verifyPaySign(publicKey, APPID_JP, jpParams));
        assertFalse(verifyPaySign(publicKey, APPID_JP, guziParams));
        assertFalse(verifyPaySign(publicKey, APPID_GUZI, jpParams));
        assertNotEquals(guziParams.paySign(), jpParams.paySign());
    }

    @Test
    @DisplayName("§A real 通道下 appid 不可用（空/wxMOCK/占位值）→ 立刻报出根因，不把烂 appid 发给微信")
    void buildPayParams_rejectsUnusableAppid_evenWhenGlobalConfigLooksFine() throws Exception {
        givenMerchantKeyPair();
        WechatPayV3ClientImpl client = new WechatPayV3ClientImpl(payProperties);

        // 全局配置里有一个「看着正常」的 appid，但上下文给的是烂值 —— 仍必须抛错（= 没在读全局兜底）
        for (String bad : new String[]{null, "", "  ", "wxMOCK", "wx_placeholder_appid", "wx_dev_placeholder"}) {
            ServiceException ex = assertThrows(ServiceException.class,
                () -> client.buildPayParams("wx_prepay", bad), "appid=" + bad + " 应被拒");
            assertTrue(ex.getMessage().contains("调起支付签名"), "报错要点明是哪一步：" + ex.getMessage());
        }
    }

    // ============================================================
    //  §B 统一下单（WechatPayV3ClientImpl:93）
    // ============================================================

    @Test
    @DisplayName("§B 统一下单 PrepayRequest.appid = 下单上下文 appid（不是全局 gz.pay.appid）")
    void createJsapiOrder_usesContextAppid() throws Exception {
        WechatPayV3ClientImpl client = new WechatPayV3ClientImpl(payProperties);
        JsapiServiceExtension jsapiService = mock(JsapiServiceExtension.class);
        PrepayWithRequestPaymentResponse resp = new PrepayWithRequestPaymentResponse();
        resp.setPackageVal("prepay_id=wx_prepay_from_sdk");
        when(jsapiService.prepayWithRequestPayment(any(PrepayRequest.class))).thenReturn(resp);
        injectField(client, "jsapiService", jsapiService);

        String prepayId = client.createJsapiOrder(
            new UnifiedOrderRequest("PINDOU-20260806-000001", 3000L, "openid_jp_001", "拼团单", APPID_JP));

        assertEquals("wx_prepay_from_sdk", prepayId);
        ArgumentCaptor<PrepayRequest> cap = ArgumentCaptor.forClass(PrepayRequest.class);
        verify(jsapiService).prepayWithRequestPayment(cap.capture());
        assertEquals(APPID_JP, cap.getValue().getAppid(), "★ 防回归：:93 必须用上下文 appid");
        assertNotEquals(APPID_GLOBAL_LEGACY, cap.getValue().getAppid());
        assertEquals("1731037015", cap.getValue().getMchid(), "商户号两个小程序共用，不变");
        assertEquals("openid_jp_001", cap.getValue().getPayer().getOpenid(),
            "payer.openid 与 appid 同源（openid 是 appid 维度标识）");
    }

    @Test
    @DisplayName("§B 统一下单 appid 不可用 → fail-fast，不发请求给微信")
    void createJsapiOrder_rejectsUnusableAppid() throws Exception {
        WechatPayV3ClientImpl client = new WechatPayV3ClientImpl(payProperties);
        JsapiServiceExtension jsapiService = mock(JsapiServiceExtension.class);
        injectField(client, "jsapiService", jsapiService);

        ServiceException ex = assertThrows(ServiceException.class, () -> client.createJsapiOrder(
            new UnifiedOrderRequest("PINDOU-20260806-000002", 3000L, "openid", "单", "wxMOCK")));
        assertTrue(ex.getMessage().contains("统一下单"));
        verify(jsapiService, org.mockito.Mockito.never()).prepayWithRequestPayment(any());
    }

    private static void injectField(Object target, String field, Object value) throws Exception {
        java.lang.reflect.Field f = target.getClass().getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }

    // ============================================================
    //  §C PayAppidResolver 优先级
    // ============================================================

    @Test
    @DisplayName("§C 两个小程序各自解析到自己的 appid（默认取登录侧 appid，与 openid 同源）")
    void resolver_perClientId() {
        givenRequestWithClientId(CID_GUZI);
        assertEquals(APPID_GUZI, resolver.resolveForCurrentApp());

        givenRequestWithClientId(CID_JP);
        assertEquals(APPID_JP, resolver.resolveForCurrentApp());
    }

    @Test
    @DisplayName("§C gz_pay_channel 配了该 clientid 的 appid → 优先级最高（ADR-0019 §3 真源激活）")
    void resolver_channelRowWins() {
        givenRequestWithClientId(CID_JP);
        when(channelMapper.selectList(any())).thenReturn(List.of(channelRow(CID_JP, "wxCHANNELWINS0001")));

        assertEquals("wxCHANNELWINS0001", resolver.resolveForCurrentApp());
    }

    @Test
    @DisplayName("§C gz_pay_channel 是建表占位值 → 视同没配，降级取登录侧 appid（prod 现状不受影响）")
    void resolver_channelPlaceholderIgnored() {
        givenRequestWithClientId(CID_GUZI);
        when(channelMapper.selectList(any())).thenReturn(List.of(channelRow(CID_GUZI, "wx_placeholder_appid")));

        assertEquals(APPID_GUZI, resolver.resolveForCurrentApp());
    }

    @Test
    @DisplayName("§C gz_pay_channel 查询抛异常 → 只降级不阻断下单（可选覆盖不得成为新失败点）")
    void resolver_channelQueryFailureDegrades() {
        givenRequestWithClientId(CID_GUZI);
        when(channelMapper.selectList(any())).thenThrow(new RuntimeException("Unknown column 'client_id'"));

        assertEquals(APPID_GUZI, assertDoesNotThrow(() -> resolver.resolveForCurrentApp()));
    }

    @Test
    @DisplayName("§C ★ 后台 PC clientid（不是小程序）→ 落默认小程序，不抛错（GZ-SYS-020 §6 同类回归）")
    void resolver_adminPcClientIdFallsBackToDefault() {
        givenRequestWithClientId(ADMIN_PC_CLIENT_ID);
        assertEquals(APPID_GUZI, assertDoesNotThrow(() -> resolver.resolveForCurrentApp()),
            "plus-ui 后台发起的通道测试单必须能下单");
    }

    @Test
    @DisplayName("§C 无请求上下文（cron / 单测 / curl）→ 落 default-client-id")
    void resolver_noRequestContext() {
        RequestContextHolder.resetRequestAttributes();
        assertEquals(APPID_GUZI, resolver.resolveForCurrentApp());
    }

    @Test
    @DisplayName("§C 单值 wx.miniapp 形态（prod 现状）→ 解析出的就是改造前 gz.pay.appid 的同一个值")
    void resolver_legacySingleValueForm() {
        WxMiniappProperties legacy = new WxMiniappProperties();
        legacy.setAppid(APPID_GUZI);
        PayAppidResolver legacyResolver =
            new PayAppidResolver(new WxAppResolver(legacy), channelMapper, payProperties);

        givenRequestWithClientId(CID_GUZI);
        assertEquals(APPID_GUZI, legacyResolver.resolveForCurrentApp());
    }

    @Test
    @DisplayName("§C wx.miniapp 完全没配 → 兜底回落全局 gz.pay.appid（历史配置形态不劣化）")
    void resolver_fallsBackToLegacyGlobal() {
        WxMiniappProperties blank = new WxMiniappProperties();
        blank.setAppid("");
        PayAppidResolver blankResolver =
            new PayAppidResolver(new WxAppResolver(blank), channelMapper, payProperties);

        assertEquals(APPID_GLOBAL_LEGACY, blankResolver.resolveForCurrentApp());
    }

    @Test
    @DisplayName("§C 三级全空 → 明确报错（部署事故，不静默签一个空 appid）")
    void resolver_allBlankThrows() {
        WxMiniappProperties blank = new WxMiniappProperties();
        blank.setAppid("");
        payProperties.setAppid(null);
        PayAppidResolver blankResolver =
            new PayAppidResolver(new WxAppResolver(blank), channelMapper, payProperties);

        ServiceException ex = assertThrows(ServiceException.class, blankResolver::resolveForCurrentApp);
        assertTrue(ex.getMessage().contains("无法确定本单使用的小程序 appid"));
    }

    private GzPayChannel channelRow(String clientId, String appid) {
        GzPayChannel row = new GzPayChannel();
        row.setId(1L);
        row.setChannelCode("wechat_pay_v3");
        row.setClientId(clientId);
        row.setAppid(appid);
        row.setEnabled(1);
        return row;
    }

    // ============================================================
    //  §D 端到端：同一进程内两个小程序各自下单、各自签名
    // ============================================================

    @Test
    @DisplayName("§D ★ AC：两个小程序各自下单、各自签名 —— 下单与签名 appid 成对、互不串号")
    void endToEnd_twoMiniapps_orderAndSignWithOwnAppid() {
        GzPayTransactionMapper txMapper = mock(GzPayTransactionMapper.class);
        GzPayCallbackLogMapper logMapper = mock(GzPayCallbackLogMapper.class);
        PayOrderNoGenerator generator = mock(PayOrderNoGenerator.class);
        IGzPayShippingService shippingService = mock(IGzPayShippingService.class);
        PayCallbackDispatcher dispatcher = mock(PayCallbackDispatcher.class);
        lenient().when(dispatcher.resolveShippingInfo(any())).thenReturn(Optional.empty());
        @SuppressWarnings("unchecked")
        ObjectProvider<PayCallbackDispatcher> dispatcherProvider = mock(ObjectProvider.class);
        lenient().when(dispatcherProvider.getObject()).thenReturn(dispatcher);

        MockWechatPayClient payClient = spy(new MockWechatPayClient(payProperties));
        Map<String, Long> seq = new HashMap<>();
        when(generator.generate(anyString())).thenAnswer(inv -> {
            String type = inv.getArgument(0);
            long n = seq.merge(type, 1L, Long::sum);
            return PayBusinessType.toOutTradePrefix(type) + "-20260806-00000" + n;
        });
        when(txMapper.insert(any(GzPayTransaction.class))).thenAnswer(inv -> {
            inv.getArgument(0, GzPayTransaction.class).setId(9001L);
            return 1;
        });
        when(txMapper.markPending(any(), anyString())).thenReturn(1);
        lenient().when(logMapper.insert(any(org.dromara.gz.common.pay.domain.entity.GzPayCallbackLog.class)))
            .thenReturn(1);

        GzPayTransactionServiceImpl service = new GzPayTransactionServiceImpl(
            txMapper, logMapper, generator, payClient, payProperties, shippingService, dispatcherProvider, resolver);

        // ① 谷子宇宙小程序下单
        givenRequestWithClientId(CID_GUZI);
        MpPayParamsVO guziVo = service.createBusinessOrder(order("BK-GUZI-001", "openid_guzi"));

        // ② 日本拼团小程序下单（同一个进程、同一个单例 service）
        givenRequestWithClientId(CID_JP);
        MpPayParamsVO jpVo = service.createBusinessOrder(order("BK-JP-001", "openid_jp"));

        ArgumentCaptor<UnifiedOrderRequest> orderCap = ArgumentCaptor.forClass(UnifiedOrderRequest.class);
        verify(payClient, org.mockito.Mockito.times(2)).createJsapiOrder(orderCap.capture());
        ArgumentCaptor<String> signAppidCap = ArgumentCaptor.forClass(String.class);
        verify(payClient, org.mockito.Mockito.times(2)).buildPayParams(anyString(), signAppidCap.capture());

        assertEquals(APPID_GUZI, orderCap.getAllValues().get(0).appid());
        assertEquals(APPID_JP, orderCap.getAllValues().get(1).appid());
        assertEquals(APPID_GUZI, signAppidCap.getAllValues().get(0), "谷子宇宙的签名用谷子宇宙 appid");
        assertEquals(APPID_JP, signAppidCap.getAllValues().get(1), "拼团的签名用拼团 appid");
        // 下单 appid 与签名 appid 必须成对（不成对 = 下单 200、调起才炸的那类事故）
        assertEquals(orderCap.getAllValues().get(0).appid(), signAppidCap.getAllValues().get(0));
        assertEquals(orderCap.getAllValues().get(1).appid(), signAppidCap.getAllValues().get(1));
        assertNotEquals(guziVo.getOutTradeNo(), jpVo.getOutTradeNo());
    }

    private CreateOrderBo order(String bizNo, String openid) {
        return CreateOrderBo.builder()
            .businessType(PayBusinessType.PINDOU)
            .businessOrderNo(bizNo)
            .amountCent(3000L)
            .openid(openid)
            .userId(11L)
            .description("拼豆")
            .build();
    }
}
