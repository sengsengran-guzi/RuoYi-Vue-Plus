package org.dromara.gz.ord.service.impl;

import org.dromara.common.core.exception.ServiceException;
import org.dromara.gz.ord.domain.bo.GzOrdProductBo;
import org.dromara.gz.ord.domain.bo.GzOrdSkuBo;
import org.dromara.gz.ord.domain.entity.GzOrdProduct;
import org.dromara.gz.ord.enums.OrdProductStatusEnum;
import org.dromara.gz.ord.exception.GzOrdErrorCode;
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

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * GzOrdProductServiceImpl 单测（GZ-ORD-101 — 截止下架 + 状态流转 + 到货日校验）。
 *
 * <p>覆盖：AC 7 ⑤ 截止下架 service（on_shelf 过期 → auto_off）；决策 D5 admin 不可手动设 auto_off；
 * F6.1 到货日二选一校验；决策 D1 SKU 列表非空校验。</p>
 */
@Tag("dev")
@DisplayName("GzOrdProductServiceImpl 单测 — 截止下架 / 状态流转 / 校验")
@ExtendWith(MockitoExtension.class)
class GzOrdProductServiceImplTest {

    @Mock
    private GzOrdProductMapper productMapper;
    @Mock
    private GzOrdSkuMapper skuMapper;
    @Mock
    private org.dromara.gz.common.service.IGzFileService fileService;

    private GzOrdProductServiceImpl service;

    @BeforeEach
    void setUp() {
        // OrdHtmlSanitizer 是无状态 @Component，直接 new（不 mock）；fileService 仅 mp 列表路径用，CRUD 单测不触
        service = new GzOrdProductServiceImpl(productMapper, skuMapper, new OrdHtmlSanitizer(), fileService);
    }

    @Test
    @DisplayName("⑤ 截止下架 service：on_shelf 过期 → auto_off（委托 mapper 批量 UPDATE）")
    void autoOffExpiredProducts() {
        when(productMapper.autoOffExpiredProducts()).thenReturn(3);

        int affected = service.autoOffExpiredProducts();

        assertEquals(3, affected);
        verify(productMapper, times(1)).autoOffExpiredProducts();
    }

    @Test
    @DisplayName("决策 D5：admin changeStatus 不可手动设 auto_off → INVALID_STATUS")
    void changeStatusRejectAutoOff() {
        ServiceException ex = assertThrows(ServiceException.class,
            () -> service.changeStatus(1L, OrdProductStatusEnum.AUTO_OFF.getCode()));
        assertEquals(GzOrdErrorCode.INVALID_STATUS, ex.getCode());
        // auto_off 被拦在 mapper 之前，不触 DB
        verify(productMapper, times(0)).selectById(anyLong());
    }

    @Test
    @DisplayName("changeStatus on_shelf 合法（manual target）→ 走 updateById")
    void changeStatusOnShelfOk() {
        GzOrdProduct p = new GzOrdProduct();
        p.setId(1L);
        p.setStatus("off_shelf");
        when(productMapper.selectById(1L)).thenReturn(p);
        when(productMapper.updateById(org.mockito.ArgumentMatchers.any(GzOrdProduct.class))).thenReturn(1);

        boolean ok = service.changeStatus(1L, OrdProductStatusEnum.ON_SHELF.getCode());

        org.junit.jupiter.api.Assertions.assertTrue(ok);
        verify(productMapper, times(1)).updateById(org.mockito.ArgumentMatchers.any(GzOrdProduct.class));
    }

    @Test
    @DisplayName("F6.1 到货日二选一：都空 → DELIVERY_DATE_INVALID")
    void insertRejectBothDeliveryDateEmpty() {
        GzOrdProductBo bo = baseBo();
        bo.setDeliveryDateText(null);
        bo.setDeliveryDateExact(null);

        ServiceException ex = assertThrows(ServiceException.class, () -> service.insertByBo(bo));
        assertEquals(GzOrdErrorCode.DELIVERY_DATE_INVALID, ex.getCode());
    }

    @Test
    @DisplayName("F6.1 到货日二选一：都填 → DELIVERY_DATE_INVALID")
    void insertRejectBothDeliveryDateFilled() {
        GzOrdProductBo bo = baseBo();
        bo.setDeliveryDateText("8 月下旬");
        bo.setDeliveryDateExact(LocalDate.of(2026, 8, 25));

        ServiceException ex = assertThrows(ServiceException.class, () -> service.insertByBo(bo));
        assertEquals(GzOrdErrorCode.DELIVERY_DATE_INVALID, ex.getCode());
    }

    @Test
    @DisplayName("决策 D1 SKU 列表非空：空列表 → SKU_LIST_EMPTY")
    void insertRejectEmptySkuList() {
        GzOrdProductBo bo = baseBo();
        bo.setDeliveryDateText("8 月下旬");
        bo.setSkuList(List.of());

        ServiceException ex = assertThrows(ServiceException.class, () -> service.insertByBo(bo));
        assertEquals(GzOrdErrorCode.SKU_LIST_EMPTY, ex.getCode());
    }

    @Test
    @DisplayName("AC 7 批量上架：auto_off 被过滤进 skipped，合法项进 success 走一次 UPDATE")
    void batchOnShelfFiltersAutoOff() {
        // id=1 off_shelf 未截止有 enabled SKU → 上架成功；id=2 auto_off → 跳过
        GzOrdProduct p1 = new GzOrdProduct();
        p1.setId(1L);
        p1.setStatus("off_shelf");
        p1.setDeadlineTime(LocalDateTime.now().plusDays(5));
        GzOrdProduct p2 = new GzOrdProduct();
        p2.setId(2L);
        p2.setStatus("auto_off");
        p2.setDeadlineTime(LocalDateTime.now().minusDays(1));
        when(productMapper.selectById(1L)).thenReturn(p1);
        when(productMapper.selectById(2L)).thenReturn(p2);
        // p1 有 1 个 enabled SKU
        when(skuMapper.selectCount(org.mockito.ArgumentMatchers.any())).thenReturn(1L);
        when(productMapper.update(org.mockito.ArgumentMatchers.any(GzOrdProduct.class), org.mockito.ArgumentMatchers.any()))
            .thenReturn(1);

        var result = service.batchUpdateStatus(List.of(1L, 2L), OrdProductStatusEnum.ON_SHELF.getCode());

        assertEquals(1, result.getSuccess().size());
        assertEquals(1L, result.getSuccess().get(0));
        assertEquals(1, result.getSkipped().size());
        assertEquals(2L, result.getSkipped().get(0).getId());
        // 合法项一次性 UPDATE IN(...)（决策 D2）
        verify(productMapper, times(1))
            .update(org.mockito.ArgumentMatchers.any(GzOrdProduct.class), org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("AC 7 批量 targetStatus=auto_off → INVALID_STATUS（决策 D5，整体拒绝不触 DB）")
    void batchRejectAutoOffTarget() {
        ServiceException ex = assertThrows(ServiceException.class,
            () -> service.batchUpdateStatus(List.of(1L), OrdProductStatusEnum.AUTO_OFF.getCode()));
        assertEquals(GzOrdErrorCode.INVALID_STATUS, ex.getCode());
        verify(productMapper, times(0)).selectById(anyLong());
    }

    @Test
    @DisplayName("AC 8 导入：到货日未二选一 → 行级错误汇总 + 整体回滚（不调 insert）")
    void importRejectDeliveryDateBothEmpty() {
        org.dromara.gz.ord.domain.excel.GzOrdProductImportVo row =
            new org.dromara.gz.ord.domain.excel.GzOrdProductImportVo();
        row.setName("CHIIKAWA 公仔");
        row.setDeadlineTime("2026-08-25 10:00:00");
        row.setSpecName("标准款");
        row.setPriceYuan("99.00");
        // deliveryDateText / deliveryDateExact 都空 → F6.1 违反

        var result = service.importData(List.of(row));

        org.junit.jupiter.api.Assertions.assertFalse(result.isSuccess());
        org.junit.jupiter.api.Assertions.assertEquals(1, result.getErrors().size());
        // 整体回滚：不触 insert
        verify(productMapper, times(0)).insert(org.mockito.ArgumentMatchers.any(GzOrdProduct.class));
    }

    private GzOrdProductBo baseBo() {
        GzOrdProductBo bo = new GzOrdProductBo();
        bo.setName("CHIIKAWA 公仔");
        bo.setIpTag("CHIIKAWA");
        bo.setDeadlineTime(LocalDateTime.now().plusDays(10));
        GzOrdSkuBo sku = new GzOrdSkuBo();
        sku.setSpecName("标准款");
        sku.setPriceCent(9900L);
        sku.setStockTotal(100);
        bo.setSkuList(List.of(sku));
        return bo;
    }
}
