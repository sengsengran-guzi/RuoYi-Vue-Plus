package org.dromara.gz.jp.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.service.DictService;
import org.dromara.gz.common.domain.vo.GzUserVO;
import org.dromara.gz.common.pay.domain.bo.CreateOrderBo;
import org.dromara.gz.common.pay.domain.vo.MpPayParamsVO;
import org.dromara.gz.common.pay.enums.PayBusinessType;
import org.dromara.gz.common.pay.service.IGzPayTransactionService;
import org.dromara.gz.common.pay.service.internal.PayOrderNoGenerator;
import org.dromara.gz.common.service.IGzFileService;
import org.dromara.gz.common.service.IGzUserService;
import org.dromara.gz.jp.domain.bo.GzJpOrderSubmitBo;
import org.dromara.gz.jp.domain.dto.JpOrderSnapshot;
import org.dromara.gz.jp.domain.entity.GzJpCartItem;
import org.dromara.gz.jp.domain.entity.GzJpOrder;
import org.dromara.gz.jp.domain.entity.GzJpOrderItem;
import org.dromara.gz.jp.domain.entity.GzJpProduct;
import org.dromara.gz.jp.domain.enums.GzJpEventStatus;
import org.dromara.gz.jp.domain.enums.GzJpFulfillStatus;
import org.dromara.gz.jp.domain.enums.GzJpItemSource;
import org.dromara.gz.jp.domain.enums.GzJpOrderStatus;
import org.dromara.gz.jp.domain.enums.GzJpProductStatus;
import org.dromara.gz.jp.domain.vo.GzJpEventOptionVO;
import org.dromara.gz.jp.domain.vo.GzJpOrderSubmitVO;
import org.dromara.gz.jp.exception.GzJpOrderErrorCode;
import org.dromara.gz.jp.mapper.GzJpOrderItemMapper;
import org.dromara.gz.jp.mapper.GzJpOrderMapper;
import org.dromara.gz.jp.mapper.GzJpProductMapper;
import org.dromara.gz.jp.service.IGzJpCartService;
import org.dromara.gz.jp.service.IGzJpEventService;
import org.dromara.gz.user.domain.vo.GzUserAddressVO;
import org.dromara.gz.user.service.IGzUserAddressService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 下单事务单测（GZ-JP-105，FLOW:F-JP-02.step4）。
 *
 * <p><b>accept 第 2 条直接跑本类</b>（「订单金额后端重算，前端篡改价不生效」）。</p>
 *
 * <p>覆盖：</p>
 * <ul>
 *   <li><b>★ 金额后端重算</b>：前端传假价（1 分）不生效 —— 订单落库额、支付建单额、返回额
 *       三处全是后端按<b>当前商品价</b>算出来的那个数</li>
 *   <li><b>★ 一单可含多场商品</b>（AC 3）—— 不按场拆单，两场的行同属一个 order_id</li>
 *   <li><b>★ 无运费</b>：合计恒 = Σ(单价×数量)，不多一分</li>
 *   <li>快照：逐行落 product_snapshot_json（含 productNo / name / mainImageId / priceCent /
 *       deliveryDateText / <b>noticeText</b> / 场名），地址落 address_snapshot_json</li>
 *   <li>失效项拦截：商品下架 / 场已结束 / 商品已删 —— 一次报全并点名是哪几件</li>
 *   <li>地址归属：不是本人的地址下不了单</li>
 *   <li><b>★ @Version 坑</b>：订单与订单行都<b>只 insert 不 updateById</b>（insert 后补写会静默丢）</li>
 *   <li>下单成功清购物车（同事务）；下单失败不清</li>
 *   <li>source 恒 batch（二期代切预留）、fulfill_status 起点 purchasing、order_no 前缀 JPO-</li>
 * </ul>
 *
 * @author kevin-coder (sensenran-guzi · GZ-JP-105)
 */
@Tag("dev")
@DisplayName("GZ-JP-105 下单事务（后端重算金额 / 多场同单 / 快照 / 失效拦截）")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GzJpOrderSubmitTest {

    private static final Long USER_ID = 2001L;
    private static final Long ADDRESS_ID = 77L;

    @Mock
    private GzJpOrderMapper orderMapper;
    @Mock
    private GzJpOrderItemMapper itemMapper;
    @Mock
    private GzJpProductMapper productMapper;
    @Mock
    private IGzJpEventService eventService;
    @Mock
    private IGzJpCartService cartService;
    @Mock
    private IGzPayTransactionService payTransactionService;
    @Mock
    private PayOrderNoGenerator orderNoGenerator;
    @Mock
    private IGzUserService userService;
    @Mock
    private IGzUserAddressService addressService;
    @Mock
    private IGzFileService fileService;
    /** 详情行的 carrierLabel 走它（下单路径不碰，这里只为满足构造） */
    @Mock
    private DictService dictService;

    private GzJpOrderServiceImpl service;

    /** in-memory「已落库」的订单行（itemMapper.insert 拦截后收集，用于断言快照与金额） */
    private final List<GzJpOrderItem> insertedItems = new ArrayList<>();
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * MP lambda 缓存：service 里有 {@code Wrappers.lambdaQuery()}（详情 / 列表路径），
     * 不 init 会在解析列名时报 "can not find lambda cache"。幂等、无副作用。
     */
    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, GzJpOrder.class);
        TableInfoHelper.initTableInfo(assistant, GzJpOrderItem.class);
    }

    @BeforeEach
    void setUp() {
        insertedItems.clear();
        service = new GzJpOrderServiceImpl(orderMapper, itemMapper, productMapper, eventService, cartService,
            payTransactionService, orderNoGenerator, userService, addressService, fileService, dictService,
            objectMapper);

        // 订单号生成器：固定发号，便于断言前缀
        when(orderNoGenerator.generate(PayBusinessType.JP)).thenReturn("JPO-20260807-000001");
        // 订单 insert：模拟 DB 回填自增主键
        when(orderMapper.insert(any(GzJpOrder.class))).thenAnswer(inv -> {
            GzJpOrder o = inv.getArgument(0);
            o.setId(9001L);
            return 1;
        });
        when(itemMapper.insert(any(GzJpOrderItem.class))).thenAnswer(inv -> {
            GzJpOrderItem item = inv.getArgument(0);
            item.setId(50_000L + insertedItems.size());
            insertedItems.add(item);
            return 1;
        });
        // 用户 / 地址：默认都合法
        GzUserVO user = new GzUserVO();
        user.setId(USER_ID);
        user.setOpenid("openid_jp_tester");
        when(userService.selectVoById(USER_ID)).thenReturn(user);
        when(addressService.getByIdForUser(eq(USER_ID), eq(ADDRESS_ID))).thenReturn(address());
        // 支付建单：返回 5 参
        when(payTransactionService.createBusinessOrder(any(CreateOrderBo.class))).thenReturn(
            MpPayParamsVO.builder().timeStamp("1").nonceStr("n").packageVal("prepay_id=mock")
                .signType("RSA").paySign("sig").outTradeNo("JPO-20260807-000002").build());
        when(cartService.deleteItems(anyLong(), any())).thenAnswer(inv -> ((java.util.Collection<?>) inv.getArgument(1)).size());
        when(fileService.getPresignedUrl(anyLong())).thenThrow(new RuntimeException("本测试不解析图片"));
    }

    // ============================================================
    //  ★ accept 第 2 条：金额后端重算，前端篡改价不生效
    // ============================================================

    @Test
    @DisplayName("★ 金额后端重算：前端传 1 分假价 → 直接拒单，绝不按假价建单")
    void submit_frontendFakedPrice_rejected() {
        givenCart(cart(11L, 1L, 4), cart(12L, 2L, 1));
        givenProducts(product(1L, 100L, 12800L, "柯南 吧唧 A赏"), product(2L, 100L, 6800L, "咒术 亚克力"));
        givenOpenEvents(100L);

        GzJpOrderSubmitBo bo = bo(List.of(11L, 12L));
        // 客人（或被篡改的请求）声称只要 1 分
        bo.setExpectedAmountCent(1L);

        ServiceException ex = assertThrows(ServiceException.class, () -> service.submit(bo, USER_ID));

        assertEquals(GzJpOrderErrorCode.PRICE_CHANGED, ex.getCode());
        // 正确金额 = 12800×4 + 6800×1 = 58000 分 = 580.00 元，报错文案里给客人看
        assertTrue(ex.getMessage().contains("580.00"), "报错要告诉客人正确金额：" + ex.getMessage());
        // ★ 关键：一分钱的支付单都没建、订单也没落库
        verify(payTransactionService, never()).createBusinessOrder(any(CreateOrderBo.class));
        verify(orderMapper, never()).insert(any(GzJpOrder.class));
        verify(cartService, never()).deleteItems(anyLong(), any());
        System.out.println("[AC2] 前端假价 1 分被拒，后端算出的正确金额=58000 分，未建任何支付单");
    }

    @Test
    @DisplayName("★ 金额后端重算：不传前端价时，落库额 / 建单额 / 返回额三处都是 Σ(当前单价×数量)")
    void submit_amountAlwaysRecomputedByBackend() {
        givenCart(cart(11L, 1L, 4), cart(12L, 2L, 1));
        // 注意：商品价是「下单这一刻」的价（12800/6800），购物车不存价格快照
        givenProducts(product(1L, 100L, 12800L, "柯南 吧唧 A赏"), product(2L, 100L, 6800L, "咒术 亚克力"));
        givenOpenEvents(100L);

        GzJpOrderSubmitVO vo = service.submit(bo(List.of(11L, 12L)), USER_ID);

        long expected = 12800L * 4 + 6800L * 1;
        assertEquals(expected, vo.getTotalAmountCent(), "返回额 = 后端重算");

        ArgumentCaptor<GzJpOrder> orderCap = ArgumentCaptor.forClass(GzJpOrder.class);
        verify(orderMapper).insert(orderCap.capture());
        assertEquals(expected, orderCap.getValue().getTotalAmountCent(), "落库额 = 后端重算");

        ArgumentCaptor<CreateOrderBo> payCap = ArgumentCaptor.forClass(CreateOrderBo.class);
        verify(payTransactionService).createBusinessOrder(payCap.capture());
        assertEquals(expected, payCap.getValue().getAmountCent(), "支付建单额 = 后端重算");
        assertEquals(PayBusinessType.JP, payCap.getValue().getBusinessType(), "business_type = jp");
        assertEquals("JPO-20260807-000001", payCap.getValue().getBusinessOrderNo(), "business_order_no = order_no");
        assertEquals("openid_jp_tester", payCap.getValue().getOpenid());

        // ★ 无运费：合计恰好 = Σ 行金额，不多一分
        long sumLines = insertedItems.stream().mapToLong(GzJpOrderItem::getAmountCent).sum();
        assertEquals(sumLines, vo.getTotalAmountCent(), "合计 = Σ 行金额（全包邮，无运费项）");
        System.out.println("[AC2] 后端重算金额=" + expected + " 分；Σ行金额=" + sumLines + "（无运费差额）");
    }

    @Test
    @DisplayName("金额比对通过时正常下单（expectedAmountCent 只做闸，不是金额来源）")
    void submit_expectedAmountMatches_ok() {
        givenCart(cart(11L, 1L, 2));
        givenProducts(product(1L, 100L, 5000L, "海贼王 挂件"));
        givenOpenEvents(100L);

        GzJpOrderSubmitBo bo = bo(List.of(11L));
        bo.setExpectedAmountCent(10000L);
        GzJpOrderSubmitVO vo = service.submit(bo, USER_ID);

        assertEquals(10000L, vo.getTotalAmountCent());
        assertEquals("JPO-20260807-000001", vo.getOrderNo());
        assertTrue(vo.getOrderNo().startsWith("JPO-"), "订单号前缀 JPO-");
        assertNotNull(vo.getPayParams(), "返回微信支付 5 参");
    }

    // ============================================================
    //  ★ AC 3：一单可含多场商品
    // ============================================================

    @Test
    @DisplayName("★ 一单可含多场商品：两场各一款 → 同一个 order_id，快照各带各的场名")
    void submit_multiEvent_singleOrder() {
        givenCart(cart(11L, 1L, 1), cart(12L, 5L, 2));
        givenProducts(product(1L, 100L, 12800L, "8月场商品"), product(5L, 200L, 3000L, "9月场商品"));
        // 两个场都 open
        Map<Long, GzJpEventOptionVO> events = new HashMap<>();
        events.put(100L, event(100L, "EVT-20260807-000001", "8月上旬快闪场"));
        events.put(200L, event(200L, "EVT-20260901-000001", "9月新番场"));
        when(eventService.selectOptionMap(any())).thenReturn(events);

        GzJpOrderSubmitVO vo = service.submit(bo(List.of(11L, 12L)), USER_ID);

        assertEquals(2, insertedItems.size(), "两行");
        assertEquals(2, vo.getItemCount());
        // 同一个订单
        assertTrue(insertedItems.stream().allMatch(i -> i.getOrderId().equals(9001L)), "两行同属一个订单");
        // 快照里的场各不相同
        List<String> eventNames = insertedItems.stream().map(i -> snapshot(i).getEventName()).toList();
        assertTrue(eventNames.contains("8月上旬快闪场") && eventNames.contains("9月新番场"),
            "两行快照分属两个场：" + eventNames);
        assertEquals(12800L * 1 + 3000L * 2, vo.getTotalAmountCent());
        System.out.println("[AC3] 一单含两场：" + eventNames + "，合计=" + vo.getTotalAmountCent() + " 分");
    }

    // ============================================================
    //  快照 / 行字段
    // ============================================================

    @Test
    @DisplayName("每行落 product_snapshot_json（含 noticeText / mainImageId / 编号 / 到货文案）")
    void submit_productSnapshotComplete() {
        givenCart(cart(11L, 1L, 3));
        GzJpProduct p = product(1L, 100L, 12800L, "柯南 吧唧 A赏");
        p.setProductNo("JPP-20260807-000001");
        p.setMainImageId(8888L);
        p.setDeliveryDateText("8月下旬");
        p.setNoticeText("★ 日本直邮，介意瑕疵慎拍");
        givenProducts(p);
        givenOpenEvents(100L);

        service.submit(bo(List.of(11L)), USER_ID);

        GzJpOrderItem item = insertedItems.get(0);
        JpOrderSnapshot.Product snap = snapshot(item);
        assertEquals("1", snap.getProductId());
        assertEquals("JPP-20260807-000001", snap.getProductNo());
        assertEquals("柯南 吧唧 A赏", snap.getName());
        assertEquals("8888", snap.getMainImageId(), "★ 存 file id 不存 URL（URL 是 1h 预签名）");
        assertEquals(12800L, snap.getPriceCent());
        assertEquals("8月下旬", snap.getDeliveryDateText());
        assertEquals("★ 日本直邮，介意瑕疵慎拍", snap.getNoticeText(), "★ 甲方点名的注意事项必须进快照");
        assertEquals("8月上旬快闪场", snap.getEventName());
        assertFalse(item.getProductSnapshotJson().contains("http"), "快照里不该出现 URL");
        System.out.println("[快照] " + item.getProductSnapshotJson());
    }

    @Test
    @DisplayName("行字段：source=batch（二期代切预留）/ fulfill_status=purchasing / 行金额=单价×数量 / 冗余 user_id")
    void submit_itemFieldsDefaults() {
        givenCart(cart(11L, 1L, 3));
        givenProducts(product(1L, 100L, 12800L, "柯南 吧唧 A赏"));
        givenOpenEvents(100L);

        service.submit(bo(List.of(11L)), USER_ID);

        GzJpOrderItem item = insertedItems.get(0);
        assertEquals(GzJpItemSource.BATCH.getCode(), item.getSource());
        assertEquals(GzJpFulfillStatus.PURCHASING.getCode(), item.getFulfillStatus());
        assertEquals(3, item.getQty());
        assertEquals(12800L, item.getUnitPriceCent());
        assertEquals(38400L, item.getAmountCent());
        assertEquals(USER_ID, item.getUserId(), "冗余 user_id（履约看板按客人聚合用）");
        assertNull(item.getCarrierCode());
        assertNull(item.getTrackingNo());
        assertNull(item.getRefundStatus());
    }

    @Test
    @DisplayName("订单头：created / 地址快照 / pay_transaction_id 建单时留空（回调再回填）")
    void submit_orderHeaderFields() {
        givenCart(cart(11L, 1L, 1));
        givenProducts(product(1L, 100L, 12800L, "柯南 吧唧 A赏"));
        givenOpenEvents(100L);

        GzJpOrderSubmitBo bo = bo(List.of(11L));
        bo.setUserNote("麻烦包严实点");
        service.submit(bo, USER_ID);

        ArgumentCaptor<GzJpOrder> cap = ArgumentCaptor.forClass(GzJpOrder.class);
        verify(orderMapper).insert(cap.capture());
        GzJpOrder order = cap.getValue();
        assertEquals(GzJpOrderStatus.CREATED.getCode(), order.getBusinessStatus());
        assertEquals(USER_ID, order.getUserId());
        assertEquals("麻烦包严实点", order.getUserNote());
        assertNull(order.getPayTransactionId(), "建单时留空，支付回调回填");
        assertNull(order.getPaidTime());
        assertEquals(0, order.getVersion());

        JpOrderSnapshot.Address addr = readAddress(order.getAddressSnapshotJson());
        assertEquals("张三", addr.getRecipient());
        assertEquals("13800000000", addr.getMobile());
        assertEquals("成都市", addr.getCity());
        assertFalse(order.getAddressSnapshotJson().contains("isDefault"), "快照不含地址簿内部字段");
    }

    // ============================================================
    //  ★ @Version 坑：只 insert，不 insert 后补 update
    // ============================================================

    @Test
    @DisplayName("★ @Version 坑：订单与订单行都只 insert，绝不 insert 后 updateById 补字段")
    void submit_neverUpdateAfterInsert() {
        givenCart(cart(11L, 1L, 1), cart(12L, 2L, 1));
        givenProducts(product(1L, 100L, 12800L, "A"), product(2L, 100L, 6800L, "B"));
        givenOpenEvents(100L);

        service.submit(bo(List.of(11L, 12L)), USER_ID);

        verify(orderMapper, times(1)).insert(any(GzJpOrder.class));
        verify(orderMapper, never()).updateById(any(GzJpOrder.class));
        verify(itemMapper, times(2)).insert(any(GzJpOrderItem.class));
        verify(itemMapper, never()).updateById(any(GzJpOrderItem.class));
        System.out.println("[@Version] 订单 1 次 insert / 行 2 次 insert / updateById 0 次");
    }

    // ============================================================
    //  失效项 / 地址 / 空勾选
    // ============================================================

    @Test
    @DisplayName("失效项拦截：商品已下架 → 4102 且点名是哪件，不建单不清车")
    void submit_productOffShelf_rejected() {
        givenCart(cart(11L, 1L, 1), cart(12L, 2L, 1));
        GzJpProduct off = product(2L, 100L, 6800L, "咒术 亚克力");
        off.setStatus(GzJpProductStatus.OFF_SHELF.getCode());
        givenProducts(product(1L, 100L, 12800L, "柯南 吧唧 A赏"), off);
        givenOpenEvents(100L);

        ServiceException ex = assertThrows(ServiceException.class, () -> service.submit(bo(List.of(11L, 12L)), USER_ID));

        assertEquals(GzJpOrderErrorCode.ITEM_INVALID, ex.getCode());
        assertTrue(ex.getMessage().contains("咒术 亚克力"), "点名失效商品：" + ex.getMessage());
        verify(orderMapper, never()).insert(any(GzJpOrder.class));
        verify(cartService, never()).deleteItems(anyLong(), any());
        verify(payTransactionService, never()).createBusinessOrder(any(CreateOrderBo.class));
    }

    @Test
    @DisplayName("失效项拦截：场已结束（写路径认严格闸 isBookable）→ 4102")
    void submit_eventClosed_rejected() {
        givenCart(cart(11L, 1L, 1));
        givenProducts(product(1L, 100L, 12800L, "柯南 吧唧 A赏"));
        Map<Long, GzJpEventOptionVO> events = new HashMap<>();
        GzJpEventOptionVO closed = event(100L, "EVT-1", "7月旧场");
        closed.setStatus(GzJpEventStatus.CLOSED.getCode());
        events.put(100L, closed);
        when(eventService.selectOptionMap(any())).thenReturn(events);

        ServiceException ex = assertThrows(ServiceException.class, () -> service.submit(bo(List.of(11L)), USER_ID));
        assertEquals(GzJpOrderErrorCode.ITEM_INVALID, ex.getCode());
        assertTrue(ex.getMessage().contains("场已结束"), ex.getMessage());
    }

    @Test
    @DisplayName("失效项拦截：商品已被删（查不到）→ 4102")
    void submit_productRemoved_rejected() {
        givenCart(cart(11L, 1L, 1));
        // 商品表查不到 = 已软删
        when(productMapper.selectByIds(any())).thenReturn(List.of());
        when(eventService.selectOptionMap(any())).thenReturn(Map.of());

        ServiceException ex = assertThrows(ServiceException.class, () -> service.submit(bo(List.of(11L)), USER_ID));
        assertEquals(GzJpOrderErrorCode.ITEM_INVALID, ex.getCode());
    }

    @Test
    @DisplayName("多件同时失效 → 一次报全（客人不用一件一件试）")
    void submit_multipleInvalid_reportedTogether() {
        givenCart(cart(11L, 1L, 1), cart(12L, 2L, 1));
        GzJpProduct off1 = product(1L, 100L, 12800L, "商品甲");
        off1.setStatus(GzJpProductStatus.OFF_SHELF.getCode());
        GzJpProduct off2 = product(2L, 100L, 6800L, "商品乙");
        off2.setStatus(GzJpProductStatus.OFF_SHELF.getCode());
        givenProducts(off1, off2);
        givenOpenEvents(100L);

        ServiceException ex = assertThrows(ServiceException.class, () -> service.submit(bo(List.of(11L, 12L)), USER_ID));
        assertTrue(ex.getMessage().contains("商品甲") && ex.getMessage().contains("商品乙"), ex.getMessage());
    }

    @Test
    @DisplayName("地址不是本人的 → 4103，不建单")
    void submit_addressNotOwned_rejected() {
        givenCart(cart(11L, 1L, 1));
        givenProducts(product(1L, 100L, 12800L, "柯南 吧唧 A赏"));
        givenOpenEvents(100L);
        when(addressService.getByIdForUser(eq(USER_ID), eq(ADDRESS_ID))).thenReturn(null);

        ServiceException ex = assertThrows(ServiceException.class, () -> service.submit(bo(List.of(11L)), USER_ID));
        assertEquals(GzJpOrderErrorCode.ADDRESS_INVALID, ex.getCode());
        verify(orderMapper, never()).insert(any(GzJpOrder.class));
    }

    @Test
    @DisplayName("勾选项一个都不属于本人 / 车已空 → 4101")
    void submit_emptySelection_rejected() {
        when(cartService.selectByUserAndIds(anyLong(), any())).thenReturn(List.of());

        ServiceException ex = assertThrows(ServiceException.class, () -> service.submit(bo(List.of(999L)), USER_ID));
        assertEquals(GzJpOrderErrorCode.EMPTY_SELECTION, ex.getCode());
        verifyNoInteractions(productMapper);
    }

    @Test
    @DisplayName("未登录 → 401")
    void submit_notLogin_rejected() {
        ServiceException ex = assertThrows(ServiceException.class, () -> service.submit(bo(List.of(11L)), null));
        assertEquals(401, ex.getCode());
    }

    // ============================================================
    //  购物车联动
    // ============================================================

    @Test
    @DisplayName("下单成功 → 清掉这些购物车行（同事务；传入的就是勾选的那批 id）")
    void submit_clearsCartOnSuccess() {
        givenCart(cart(11L, 1L, 1), cart(12L, 2L, 1));
        givenProducts(product(1L, 100L, 12800L, "A"), product(2L, 100L, 6800L, "B"));
        givenOpenEvents(100L);

        service.submit(bo(List.of(11L, 12L)), USER_ID);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Long>> cap = ArgumentCaptor.forClass(List.class);
        verify(cartService).deleteItems(eq(USER_ID), cap.capture());
        assertEquals(List.of(11L, 12L), cap.getValue());
    }

    @Test
    @DisplayName("★ 防重复提交：清车时删到的行数对不上（并发已被别人消费）→ 4107 整单回滚")
    void submit_concurrentDuplicate_rejected() {
        givenCart(cart(11L, 1L, 1), cart(12L, 2L, 1));
        givenProducts(product(1L, 100L, 12800L, "A"), product(2L, 100L, 6800L, "B"));
        givenOpenEvents(100L);
        // 读到 2 行，但真正 DELETE 时只剩 1 行 —— 说明另一个并发事务刚把车消费掉了
        org.mockito.Mockito.doReturn(1).when(cartService).deleteItems(anyLong(), any());

        ServiceException ex = assertThrows(ServiceException.class, () -> service.submit(bo(List.of(11L, 12L)), USER_ID));

        assertEquals(GzJpOrderErrorCode.DUPLICATE_SUBMIT, ex.getCode());
        // 抛在建支付单之前 → 不会产生一笔没人认领的支付流水（订单行的 insert 由事务回滚兜底）
        verify(payTransactionService, never()).createBusinessOrder(any(CreateOrderBo.class));
        System.out.println("[防重复提交] 读 2 行 / 实删 1 行 → 4107，且未建支付单");
    }

    @Test
    @DisplayName("正常路径：清车行数与读到的行数一致 → 放行")
    void submit_clearedCountMatches_ok() {
        givenCart(cart(11L, 1L, 1), cart(12L, 2L, 1));
        givenProducts(product(1L, 100L, 12800L, "A"), product(2L, 100L, 6800L, "B"));
        givenOpenEvents(100L);
        org.mockito.Mockito.doReturn(2).when(cartService).deleteItems(anyLong(), any());

        assertNotNull(service.submit(bo(List.of(11L, 12L)), USER_ID));
        verify(payTransactionService).createBusinessOrder(any(CreateOrderBo.class));
    }

    @Test
    @DisplayName("★ 下单流程不碰 addItem（它是 NOT_SUPPORTED 传播，会挂起本事务独立提交）")
    void submit_neverCallsAddItem() {
        givenCart(cart(11L, 1L, 1));
        givenProducts(product(1L, 100L, 12800L, "A"));
        givenOpenEvents(100L);

        service.submit(bo(List.of(11L)), USER_ID);

        verify(cartService, never()).addItem(anyLong(), anyLong(), any());
    }

    // ============================================================
    //  order_no 撞号自愈
    // ============================================================

    @Test
    @DisplayName("order_no 撞 UNIQUE → 先把 Redis 序号对齐 DB 再重试（prod 曾因计数器落后连续冲突）")
    void submit_orderNoDuplicate_reconcilesAndRetries() {
        givenCart(cart(11L, 1L, 1));
        givenProducts(product(1L, 100L, 12800L, "A"));
        givenOpenEvents(100L);

        when(orderNoGenerator.generate(PayBusinessType.JP))
            .thenReturn("JPO-20260807-000001", "JPO-20260807-000009");
        when(orderMapper.insert(any(GzJpOrder.class)))
            .thenThrow(new org.springframework.dao.DuplicateKeyException("uk_order_no"))
            .thenAnswer(inv -> {
                ((GzJpOrder) inv.getArgument(0)).setId(9002L);
                return 1;
            });

        GzJpOrderSubmitVO vo = service.submit(bo(List.of(11L)), USER_ID);

        assertEquals("JPO-20260807-000009", vo.getOrderNo(), "用重试后的号");
        verify(orderNoGenerator, times(1)).reconcileOutTradeNoToDbMax(PayBusinessType.JP);
        System.out.println("[撞号自愈] 首次撞 uk_order_no → reconcile → 第二个号成功：" + vo.getOrderNo());
    }

    // ============================================================
    //  fixtures
    // ============================================================

    private GzJpOrderSubmitBo bo(List<Long> cartItemIds) {
        GzJpOrderSubmitBo bo = new GzJpOrderSubmitBo();
        bo.setCartItemIds(cartItemIds);
        bo.setAddressId(ADDRESS_ID);
        return bo;
    }

    private void givenCart(GzJpCartItem... items) {
        when(cartService.selectByUserAndIds(anyLong(), any())).thenReturn(Arrays.asList(items));
    }

    private void givenProducts(GzJpProduct... products) {
        when(productMapper.selectByIds(any())).thenReturn(Arrays.asList(products));
    }

    private void givenOpenEvents(Long... eventIds) {
        Map<Long, GzJpEventOptionVO> map = new HashMap<>();
        for (Long id : eventIds) {
            map.put(id, event(id, "EVT-2026-" + id, "8月上旬快闪场"));
        }
        when(eventService.selectOptionMap(any())).thenReturn(map);
    }

    private GzJpCartItem cart(Long id, Long productId, int qty) {
        GzJpCartItem ci = new GzJpCartItem();
        ci.setId(id);
        ci.setUserId(USER_ID);
        ci.setProductId(productId);
        ci.setQty(qty);
        return ci;
    }

    private GzJpProduct product(Long id, Long eventId, Long priceCent, String name) {
        GzJpProduct p = new GzJpProduct();
        p.setId(id);
        p.setEventId(eventId);
        p.setName(name);
        p.setPriceCent(priceCent);
        p.setStatus(GzJpProductStatus.ON_SHELF.getCode());
        return p;
    }

    private GzJpEventOptionVO event(Long id, String eventNo, String name) {
        GzJpEventOptionVO e = new GzJpEventOptionVO();
        e.setId(id);
        e.setEventNo(eventNo);
        e.setName(name);
        e.setStatus(GzJpEventStatus.OPEN.getCode());
        return e;
    }

    private GzUserAddressVO address() {
        GzUserAddressVO a = new GzUserAddressVO();
        a.setId(ADDRESS_ID);
        a.setRecipientName("张三");
        a.setMobile("13800000000");
        a.setProvince("四川省");
        a.setCity("成都市");
        a.setDistrict("锦江区");
        a.setDetail("春熙路 1 号");
        a.setIsDefault(1);
        return a;
    }

    private JpOrderSnapshot.Product snapshot(GzJpOrderItem item) {
        try {
            return objectMapper.readValue(item.getProductSnapshotJson(), JpOrderSnapshot.Product.class);
        } catch (Exception e) {
            throw new IllegalStateException("快照反序列化失败: " + item.getProductSnapshotJson(), e);
        }
    }

    private JpOrderSnapshot.Address readAddress(String json) {
        try {
            return objectMapper.readValue(json, JpOrderSnapshot.Address.class);
        } catch (Exception e) {
            throw new IllegalStateException("地址快照反序列化失败: " + json, e);
        }
    }

    /**
     * VO / 实体<b>不得出现运费字段</b>（REQ-ORDER-004 全包邮）—— 反射扫一遍字段名，
     * 防将来有人"顺手"加回来。
     */
    @Test
    @DisplayName("★ 全链路无运费字段（订单 / 订单行 / 提交返回 VO 逐个反射扫）")
    void noFreightFieldAnywhere() {
        for (Class<?> type : List.of(GzJpOrder.class, GzJpOrderItem.class, GzJpOrderSubmitVO.class,
            org.dromara.gz.jp.domain.vo.GzJpOrderDetailVO.class,
            org.dromara.gz.jp.domain.vo.GzJpOrderItemVO.class,
            org.dromara.gz.jp.domain.vo.GzJpOrderListItemVO.class,
            GzJpOrderSubmitBo.class)) {
            for (Field f : type.getDeclaredFields()) {
                String n = f.getName().toLowerCase();
                assertFalse(n.contains("freight") || n.contains("shipping") || n.contains("postage")
                        || n.contains("deliveryfee"),
                    type.getSimpleName() + " 出现了运费字段：" + f.getName());
            }
        }
        System.out.println("[无运费] 7 个类字段全扫，无 freight / shipping / postage");
    }
}
