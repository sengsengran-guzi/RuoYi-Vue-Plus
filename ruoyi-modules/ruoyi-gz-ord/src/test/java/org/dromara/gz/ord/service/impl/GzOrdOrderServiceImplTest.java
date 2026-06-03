package org.dromara.gz.ord.service.impl;

import org.dromara.common.core.exception.ServiceException;
import org.dromara.gz.common.domain.vo.GzUserVO;
import org.dromara.gz.common.pay.domain.bo.CreateOrderBo;
import org.dromara.gz.common.pay.domain.entity.GzPayTransaction;
import org.dromara.gz.common.pay.domain.vo.MpPayParamsVO;
import org.dromara.gz.common.pay.enums.PayBusinessType;
import org.dromara.gz.common.pay.service.IGzPayTransactionService;
import org.dromara.gz.common.pay.service.internal.PayOrderNoGenerator;
import org.dromara.gz.common.service.IGzUserService;
import org.dromara.gz.ord.domain.dto.applet.SubmitOrderReq;
import org.dromara.gz.ord.domain.entity.GzOrdOrder;
import org.dromara.gz.ord.domain.entity.GzOrdProduct;
import org.dromara.gz.ord.domain.entity.GzOrdSku;
import org.dromara.gz.ord.domain.vo.applet.SubmitOrderVO;
import org.dromara.gz.ord.enums.LogisticsStatusEnum;
import org.dromara.gz.ord.enums.OrdBusinessStatusEnum;
import org.dromara.gz.ord.exception.GzOrdErrorCode;
import org.dromara.gz.ord.mapper.GzOrdOrderMapper;
import org.dromara.gz.ord.mapper.GzOrdProductMapper;
import org.dromara.gz.ord.mapper.GzOrdSkuMapper;
import org.dromara.gz.ord.service.IGzOrdSkuService;
import org.dromara.gz.user.domain.vo.GzUserAddressVO;
import org.dromara.gz.user.service.IGzUserAddressService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * GzOrdOrderServiceImpl 单测（GZ-ORD-104 AC7，≥ 5 个测试方法）。
 *
 * <p>覆盖 doc/10 §7.N6 / §7.N8 / §7.E1·E2·E3·E8：</p>
 * <ul>
 *   <li>① happy path：submit → 扣库存 → INSERT created（snapshot+in_japan）→ 调 PAY(preorder) → 返回 5 参</li>
 *   <li>② 模拟 onPaid：created → paid + paid_time + pay_transaction_id 回填 + 销量累加</li>
 *   <li>③ 库存不足：tryDeductStock 抛 SKU_OUT_OF_STOCK → 整事务回滚（不调 PAY、不 INSERT order 后续）</li>
 *   <li>④ 商品下架/截止：不落 order（PRODUCT_OFF，扣库存前拦截）</li>
 *   <li>⑤ 地址非本人：ADDRESS_INVALID（扣库存前拦截）</li>
 *   <li>⑥ 重复回调：onPaid markPaid affected=0 → 幂等不二次推进、不二次累加销量</li>
 *   <li>⑦ cancel created → cancelled + 回滚库存；cancel paid → ORDER_NOT_CANCELLABLE 不回滚库存</li>
 * </ul>
 *
 * <p>collaborators 全 mock（mapper / PAY service / 库存 service / 用户 / 地址）—— 不依赖 Spring 上下文
 * / 真实 DB，验编排逻辑 + 状态机 + 跨域调用契约 + 幂等。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-ORD-104)
 */
@Tag("dev")
@DisplayName("GzOrdOrderServiceImpl 单测 — 跨域下单 / 回调 / 取消")
@ExtendWith(MockitoExtension.class)
class GzOrdOrderServiceImplTest {

    @Mock
    private GzOrdOrderMapper orderMapper;
    @Mock
    private GzOrdProductMapper productMapper;
    @Mock
    private GzOrdSkuMapper skuMapper;
    @Mock
    private IGzOrdSkuService skuService;
    @Mock
    private IGzPayTransactionService payTransactionService;
    @Mock
    private PayOrderNoGenerator orderNoGenerator;
    @Mock
    private IGzUserService userService;
    @Mock
    private IGzUserAddressService addressService;
    @Mock
    private org.dromara.gz.common.service.IGzFileService fileService;

    private GzOrdOrderServiceImpl service;

    private static final Long USER_ID = 2001L;
    private static final Long PRODUCT_ID = 12L;
    private static final Long SKU_ID = 100L;
    private static final Long ADDRESS_ID = 5L;

    @BeforeEach
    void setUp() {
        com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
        om.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
        service = new GzOrdOrderServiceImpl(orderMapper, productMapper, skuMapper, skuService,
            payTransactionService, orderNoGenerator, userService, addressService, fileService, om);
    }

    private SubmitOrderReq req(int qty) {
        SubmitOrderReq r = new SubmitOrderReq();
        r.setProductId(PRODUCT_ID);
        r.setSkuId(SKU_ID);
        r.setQty(qty);
        r.setAddressId(ADDRESS_ID);
        r.setUserNote("尽快发货");
        return r;
    }

    private GzOrdProduct onShelfProduct() {
        GzOrdProduct p = new GzOrdProduct();
        p.setId(PRODUCT_ID);
        p.setProductNo("PRD-20260603-000001");
        p.setName("CHIIKAWA 限定盲盒");
        p.setStatus("on_shelf");
        p.setDeadlineTime(LocalDateTime.now().plusDays(10));
        p.setIpTag("CHIIKAWA");
        p.setDeliveryDateText("8 月下旬");
        return p;
    }

    private GzOrdSku enabledSku(long priceCent) {
        GzOrdSku s = new GzOrdSku();
        s.setId(SKU_ID);
        s.setProductId(PRODUCT_ID);
        s.setSkuNo("SKU-20260603-000001");
        s.setSpecName("标准款");
        s.setPriceCent(priceCent);
        s.setEnabled(1);
        s.setStockRemain(50);
        return s;
    }

    private GzUserAddressVO ownerAddress() {
        GzUserAddressVO a = new GzUserAddressVO();
        a.setId(ADDRESS_ID);
        a.setRecipientName("李茂森");
        a.setMobile("13800000000");
        a.setProvince("四川省");
        a.setCity("成都市");
        a.setDistrict("武侯区");
        a.setDetail("天府大道 1 号");
        return a;
    }

    private GzUserVO userWithOpenid() {
        GzUserVO u = new GzUserVO();
        u.setId(USER_ID);
        u.setOpenid("openid_buyer_001");
        return u;
    }

    private void wireHappyPathStubs(long priceCent) {
        // lenient：out-of-stock 等早退测试不会走到 orderNoGenerator/insert（避免 UnnecessaryStubbing）
        lenient().when(productMapper.selectById(PRODUCT_ID)).thenReturn(onShelfProduct());
        lenient().when(skuMapper.selectById(SKU_ID)).thenReturn(enabledSku(priceCent));
        lenient().when(addressService.getByIdForUser(USER_ID, ADDRESS_ID)).thenReturn(ownerAddress());
        lenient().when(userService.selectVoById(USER_ID)).thenReturn(userWithOpenid());
        lenient().when(orderNoGenerator.generate(PayBusinessType.PREORDER)).thenReturn("PREORD-20260603-000001");
    }

    @Test
    @DisplayName("① happy path：扣库存→INSERT created(snapshot+in_japan)→调 PAY(preorder)→返回 5 参")
    void submit_happyPath() {
        wireHappyPathStubs(12900L);
        // INSERT 时分配 id
        when(orderMapper.insert(any(GzOrdOrder.class))).thenAnswer(inv -> {
            ((GzOrdOrder) inv.getArgument(0)).setId(8001L);
            return 1;
        });
        when(payTransactionService.createBusinessOrder(any(CreateOrderBo.class))).thenReturn(
            MpPayParamsVO.builder().outTradeNo("PREORD-20260603-000001").paySign("sig").build());

        SubmitOrderVO vo = service.submit(req(2), USER_ID);

        // 返回值
        assertEquals(8001L, vo.getOrdOrderId());
        assertEquals("PREORD-20260603-000001", vo.getOrderNo());
        assertNotNull(vo.getPayParams());

        // 扣库存被调用（qty=2）
        verify(skuService, times(1)).tryDeductStock(SKU_ID, 2);

        // INSERT 的订单：created + in_japan + total = 12900×2 + 三段 snapshot 非空
        ArgumentCaptor<GzOrdOrder> orderCap = ArgumentCaptor.forClass(GzOrdOrder.class);
        verify(orderMapper).insert(orderCap.capture());
        GzOrdOrder inserted = orderCap.getValue();
        assertEquals(OrdBusinessStatusEnum.CREATED.getCode(), inserted.getBusinessStatus());
        assertEquals(LogisticsStatusEnum.IN_JAPAN.getCode(), inserted.getLogisticsStatus());
        assertEquals(25800L, inserted.getTotalAmountCent());
        assertEquals(2, inserted.getQty());
        assertNotNull(inserted.getProductSnapshotJson());
        assertNotNull(inserted.getSkuSnapshotJson());
        assertNotNull(inserted.getAddressSnapshotJson());
        org.junit.jupiter.api.Assertions.assertNull(inserted.getPayTransactionId(), "建单时 pay_transaction_id 为 NULL（回调回填）");

        // 调 PAY 的 BO：business_type=preorder + businessOrderNo=order_no + amount 重算 + openid
        ArgumentCaptor<CreateOrderBo> payCap = ArgumentCaptor.forClass(CreateOrderBo.class);
        verify(payTransactionService).createBusinessOrder(payCap.capture());
        CreateOrderBo payBo = payCap.getValue();
        assertEquals(PayBusinessType.PREORDER, payBo.getBusinessType());
        assertEquals("PREORD-20260603-000001", payBo.getBusinessOrderNo());
        assertEquals(25800L, payBo.getAmountCent());
        assertEquals("openid_buyer_001", payBo.getOpenid());
    }

    @Test
    @DisplayName("② 模拟 onPaid：created→paid + paid_time + pay_transaction_id 回填 + 累加销量")
    void onPaid_createdToPaid() {
        GzOrdOrder order = new GzOrdOrder();
        order.setId(8001L);
        order.setOrderNo("PREORD-20260603-000001");
        order.setProductId(PRODUCT_ID);
        order.setQty(2);
        order.setBusinessStatus(OrdBusinessStatusEnum.CREATED.getCode());
        when(orderMapper.selectByOrderNoForUpdate("PREORD-20260603-000001")).thenReturn(order);
        when(orderMapper.markPaid(eq(8001L), eq("wx_txn_123"), any(LocalDateTime.class))).thenReturn(1);

        GzPayTransaction txn = new GzPayTransaction();
        txn.setBusinessOrderNo("PREORD-20260603-000001");
        txn.setOutTradeNo("PREORD-20260603-000001");
        txn.setTransactionId("wx_txn_123");
        txn.setPaidTime(LocalDateTime.now());

        service.onPaid(txn);

        verify(orderMapper, times(1)).markPaid(eq(8001L), eq("wx_txn_123"), any(LocalDateTime.class));
        // 累加销量（已支付才累加）
        verify(productMapper, times(1)).increaseSalesCount(PRODUCT_ID, 2L);
    }

    @Test
    @DisplayName("③ 库存不足：tryDeductStock 抛 SKU_OUT_OF_STOCK → 不调 PAY（整事务回滚）")
    void submit_outOfStock_rollback() {
        wireHappyPathStubs(12900L);
        org.mockito.Mockito.doThrow(new ServiceException(GzOrdErrorCode.SKU_OUT_OF_STOCK_MSG, GzOrdErrorCode.SKU_OUT_OF_STOCK))
            .when(skuService).tryDeductStock(SKU_ID, 2);

        ServiceException ex = assertThrows(ServiceException.class, () -> service.submit(req(2), USER_ID));
        assertEquals(GzOrdErrorCode.SKU_OUT_OF_STOCK, ex.getCode());
        // 扣库存失败 → 不 INSERT order、不调 PAY（整事务回滚由 @Transactional 保证，此处验后续步骤未执行）
        verify(orderMapper, never()).insert(any(GzOrdOrder.class));
        verify(payTransactionService, never()).createBusinessOrder(any(CreateOrderBo.class));
    }

    @Test
    @DisplayName("④ 商品下架/截止：PRODUCT_OFF → 扣库存前拦截，不落 order")
    void submit_productOff() {
        GzOrdProduct off = onShelfProduct();
        off.setStatus("off_shelf");
        when(productMapper.selectById(PRODUCT_ID)).thenReturn(off);

        ServiceException ex = assertThrows(ServiceException.class, () -> service.submit(req(1), USER_ID));
        assertEquals(GzOrdErrorCode.PRODUCT_OFF, ex.getCode());
        verify(skuService, never()).tryDeductStock(anyLong(), anyInt());
        verify(orderMapper, never()).insert(any(GzOrdOrder.class));
        verify(payTransactionService, never()).createBusinessOrder(any(CreateOrderBo.class));
    }

    @Test
    @DisplayName("④b 商品已截止（deadline 过）：PRODUCT_OFF")
    void submit_deadlinePassed() {
        GzOrdProduct expired = onShelfProduct();
        expired.setDeadlineTime(LocalDateTime.now().minusMinutes(1));
        when(productMapper.selectById(PRODUCT_ID)).thenReturn(expired);

        ServiceException ex = assertThrows(ServiceException.class, () -> service.submit(req(1), USER_ID));
        assertEquals(GzOrdErrorCode.PRODUCT_OFF, ex.getCode());
        verify(skuService, never()).tryDeductStock(anyLong(), anyInt());
    }

    @Test
    @DisplayName("⑤ 地址非本人：ADDRESS_INVALID → 扣库存前拦截")
    void submit_addressInvalid() {
        when(productMapper.selectById(PRODUCT_ID)).thenReturn(onShelfProduct());
        when(skuMapper.selectById(SKU_ID)).thenReturn(enabledSku(12900L));
        when(addressService.getByIdForUser(USER_ID, ADDRESS_ID)).thenReturn(null); // 非本人 → null

        ServiceException ex = assertThrows(ServiceException.class, () -> service.submit(req(1), USER_ID));
        assertEquals(GzOrdErrorCode.ADDRESS_INVALID, ex.getCode());
        verify(skuService, never()).tryDeductStock(anyLong(), anyInt());
        verify(orderMapper, never()).insert(any(GzOrdOrder.class));
    }

    @Test
    @DisplayName("⑥ 重复回调：onPaid markPaid affected=0 → 幂等不二次推进、不二次累加销量")
    void onPaid_idempotent() {
        GzOrdOrder paidOrder = new GzOrdOrder();
        paidOrder.setId(8001L);
        paidOrder.setOrderNo("PREORD-20260603-000001");
        paidOrder.setProductId(PRODUCT_ID);
        paidOrder.setQty(2);
        paidOrder.setBusinessStatus(OrdBusinessStatusEnum.PAID.getCode());
        when(orderMapper.selectByOrderNoForUpdate("PREORD-20260603-000001")).thenReturn(paidOrder);
        when(orderMapper.markPaid(eq(8001L), anyString(), any(LocalDateTime.class))).thenReturn(0); // 已 paid → 0

        GzPayTransaction txn = new GzPayTransaction();
        txn.setBusinessOrderNo("PREORD-20260603-000001");
        txn.setTransactionId("wx_txn_123");
        txn.setPaidTime(LocalDateTime.now());

        service.onPaid(txn);

        // 幂等：markPaid 返回 0 → 不累加销量
        verify(productMapper, never()).increaseSalesCount(anyLong(), anyLong());
    }

    @Test
    @DisplayName("⑦ cancel created → cancelled + 回滚库存")
    void cancel_created_restoresStock() {
        GzOrdOrder order = new GzOrdOrder();
        order.setId(8001L);
        order.setOrderNo("PREORD-20260603-000001");
        order.setUserId(USER_ID);
        order.setSkuId(SKU_ID);
        order.setQty(2);
        order.setBusinessStatus(OrdBusinessStatusEnum.CREATED.getCode());
        when(orderMapper.selectByIdForUpdate(8001L)).thenReturn(order);
        when(orderMapper.markCancelled(eq(8001L), any(LocalDateTime.class))).thenReturn(1);

        service.cancel(8001L, USER_ID);

        verify(orderMapper, times(1)).markCancelled(eq(8001L), any(LocalDateTime.class));
        verify(skuService, times(1)).restoreStock(SKU_ID, 2);
    }

    @Test
    @DisplayName("⑦b cancel 已 paid → ORDER_NOT_CANCELLABLE，不回滚库存")
    void cancel_paid_rejected() {
        GzOrdOrder paid = new GzOrdOrder();
        paid.setId(8001L);
        paid.setUserId(USER_ID);
        paid.setSkuId(SKU_ID);
        paid.setQty(2);
        paid.setBusinessStatus(OrdBusinessStatusEnum.PAID.getCode());
        when(orderMapper.selectByIdForUpdate(8001L)).thenReturn(paid);

        ServiceException ex = assertThrows(ServiceException.class, () -> service.cancel(8001L, USER_ID));
        assertEquals(GzOrdErrorCode.ORDER_NOT_CANCELLABLE, ex.getCode());
        verify(orderMapper, never()).markCancelled(anyLong(), any(LocalDateTime.class));
        verify(skuService, never()).restoreStock(anyLong(), anyInt());
    }
}
