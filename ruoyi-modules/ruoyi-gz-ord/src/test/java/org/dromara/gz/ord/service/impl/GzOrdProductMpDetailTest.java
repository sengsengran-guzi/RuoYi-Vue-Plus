package org.dromara.gz.ord.service.impl;

import org.dromara.gz.ord.domain.dto.applet.ValidatePurchaseReq;
import org.dromara.gz.ord.domain.entity.GzOrdProduct;
import org.dromara.gz.ord.domain.entity.GzOrdSku;
import org.dromara.gz.ord.domain.vo.applet.OrdProductDetailVO;
import org.dromara.gz.ord.domain.vo.applet.ValidatePurchaseVO;
import org.dromara.gz.ord.enums.OrdErrCodeEnum;
import org.dromara.gz.ord.enums.OrdProductStatusEnum;
import org.dromara.gz.ord.mapper.GzOrdProductMapper;
import org.dromara.gz.ord.mapper.GzOrdSkuMapper;
import org.dromara.gz.ord.service.internal.OrdHtmlSanitizer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * GZ-ORD-103 mp 详情 + 下单前校验单测（Tier 1A / AC1·AC3）。
 *
 * <p>覆盖 validatePurchase 五分支：OK / PRODUCT_OFF（下架）/ PRODUCT_OFF（截止已过）/ SKU_OUT_OF_STOCK
 * （库存不足 + 停用）/ PRODUCT_NOT_FOUND（商品 / SKU 不存在）+ 无限库存（stock_remain NULL）放行；
 * getDetailForMp 商品不存在 → null。fileService 仅图片解析路径触，本测不构造主图 → lenient。</p>
 */
@Tag("dev")
@DisplayName("GzOrdProductServiceImpl — mp 详情 / 下单前校验（ORD-103）")
@ExtendWith(MockitoExtension.class)
class GzOrdProductMpDetailTest {

    @Mock
    private GzOrdProductMapper productMapper;
    @Mock
    private GzOrdSkuMapper skuMapper;
    @Mock
    private org.dromara.gz.common.service.IGzFileService fileService;

    private GzOrdProductServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new GzOrdProductServiceImpl(productMapper, skuMapper, new OrdHtmlSanitizer(), fileService);
    }

    /** 在售商品（未截止）。 */
    private GzOrdProduct onSaleProduct() {
        GzOrdProduct p = new GzOrdProduct();
        p.setId(12L);
        p.setName("CHIIKAWA 公仔");
        p.setStatus(OrdProductStatusEnum.ON_SHELF.getCode());
        p.setDeadlineTime(LocalDateTime.now().plusDays(5));
        return p;
    }

    /** 可售 SKU（库存足）。 */
    private GzOrdSku sku(Integer stockRemain, int enabled) {
        GzOrdSku s = new GzOrdSku();
        s.setId(100L);
        s.setProductId(12L);
        s.setPriceCent(12900L);
        s.setStockRemain(stockRemain);
        s.setEnabled(enabled);
        return s;
    }

    private ValidatePurchaseReq req(int qty) {
        ValidatePurchaseReq r = new ValidatePurchaseReq();
        r.setProductId(12L);
        r.setSkuId(100L);
        r.setQuantity(qty);
        return r;
    }

    @Test
    @DisplayName("OK：在售 + 库存足 → ok=true / errCode=OK")
    void validateOk() {
        when(productMapper.selectById(12L)).thenReturn(onSaleProduct());
        when(skuMapper.selectById(100L)).thenReturn(sku(50, 1));

        ValidatePurchaseVO vo = service.validatePurchase(req(2));

        assertTrue(vo.isOk());
        assertEquals(OrdErrCodeEnum.OK.getCode(), vo.getErrCode());
    }

    @Test
    @DisplayName("无限库存（stock_remain NULL）→ 放行 OK")
    void validateUnlimitedStock() {
        when(productMapper.selectById(12L)).thenReturn(onSaleProduct());
        when(skuMapper.selectById(100L)).thenReturn(sku(null, 1));

        ValidatePurchaseVO vo = service.validatePurchase(req(999));

        assertTrue(vo.isOk());
    }

    @Test
    @DisplayName("PRODUCT_OFF：商品已下架（status=off_shelf）")
    void validateProductOffShelf() {
        GzOrdProduct p = onSaleProduct();
        p.setStatus(OrdProductStatusEnum.OFF_SHELF.getCode());
        when(productMapper.selectById(12L)).thenReturn(p);

        ValidatePurchaseVO vo = service.validatePurchase(req(1));

        assertFalse(vo.isOk());
        assertEquals(OrdErrCodeEnum.PRODUCT_OFF.getCode(), vo.getErrCode());
    }

    @Test
    @DisplayName("PRODUCT_OFF：截止已过（deadline < now）")
    void validateDeadlinePassed() {
        GzOrdProduct p = onSaleProduct();
        p.setDeadlineTime(LocalDateTime.now().minusMinutes(1));
        when(productMapper.selectById(12L)).thenReturn(p);

        ValidatePurchaseVO vo = service.validatePurchase(req(1));

        assertFalse(vo.isOk());
        assertEquals(OrdErrCodeEnum.PRODUCT_OFF.getCode(), vo.getErrCode());
    }

    @Test
    @DisplayName("SKU_OUT_OF_STOCK：库存不足（stock_remain < quantity）")
    void validateStockNotEnough() {
        when(productMapper.selectById(12L)).thenReturn(onSaleProduct());
        when(skuMapper.selectById(100L)).thenReturn(sku(1, 1));

        ValidatePurchaseVO vo = service.validatePurchase(req(2));

        assertFalse(vo.isOk());
        assertEquals(OrdErrCodeEnum.SKU_OUT_OF_STOCK.getCode(), vo.getErrCode());
    }

    @Test
    @DisplayName("SKU_OUT_OF_STOCK：SKU 已停用（enabled=0）")
    void validateSkuDisabled() {
        when(productMapper.selectById(12L)).thenReturn(onSaleProduct());
        when(skuMapper.selectById(100L)).thenReturn(sku(50, 0));

        ValidatePurchaseVO vo = service.validatePurchase(req(1));

        assertFalse(vo.isOk());
        assertEquals(OrdErrCodeEnum.SKU_OUT_OF_STOCK.getCode(), vo.getErrCode());
    }

    @Test
    @DisplayName("PRODUCT_NOT_FOUND：商品不存在")
    void validateProductNotFound() {
        when(productMapper.selectById(12L)).thenReturn(null);

        ValidatePurchaseVO vo = service.validatePurchase(req(1));

        assertFalse(vo.isOk());
        assertEquals(OrdErrCodeEnum.PRODUCT_NOT_FOUND.getCode(), vo.getErrCode());
    }

    @Test
    @DisplayName("PRODUCT_NOT_FOUND：SKU 不存在 / 不属于该商品")
    void validateSkuNotFound() {
        when(productMapper.selectById(12L)).thenReturn(onSaleProduct());
        when(skuMapper.selectById(100L)).thenReturn(null);

        ValidatePurchaseVO vo = service.validatePurchase(req(1));

        assertFalse(vo.isOk());
        assertEquals(OrdErrCodeEnum.PRODUCT_NOT_FOUND.getCode(), vo.getErrCode());
    }

    @Test
    @DisplayName("getDetailForMp：商品不存在 → null")
    void detailNotFound() {
        when(productMapper.selectById(99L)).thenReturn(null);
        assertNull(service.getDetailForMp(99L));
    }

    @Test
    @DisplayName("getDetailForMp：含 SKU 列表 + serverNow（下架商品仍可看详情）")
    void detailOffShelfStillReadable() {
        GzOrdProduct p = onSaleProduct();
        p.setStatus(OrdProductStatusEnum.OFF_SHELF.getCode());
        when(productMapper.selectById(12L)).thenReturn(p);
        when(skuMapper.selectList(any())).thenReturn(List.of(sku(50, 1)));
        // 无主图 / 图集 → 不触 fileService（lenient 容忍 unnecessary stubbing 缺省）
        lenient().when(fileService.getPresignedUrl(any())).thenReturn(null);

        OrdProductDetailVO vo = service.getDetailForMp(12L);

        assertEquals("12", vo.getId().toString());
        assertEquals(OrdProductStatusEnum.OFF_SHELF.getCode(), vo.getStatus());
        assertEquals(1, vo.getSkus().size());
        assertEquals("100", vo.getSkus().get(0).getId().toString());
        org.junit.jupiter.api.Assertions.assertNotNull(vo.getServerNow());
    }
}
