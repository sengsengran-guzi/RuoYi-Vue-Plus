package org.dromara.gz.ord.service.impl;

import org.dromara.common.core.exception.ServiceException;
import org.dromara.gz.ord.domain.entity.GzOrdSku;
import org.dromara.gz.ord.exception.GzOrdErrorCode;
import org.dromara.gz.ord.mapper.GzOrdSkuMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * GzOrdSkuServiceImpl 单测（GZ-ORD-101 AC 7 — tryDeductStock 库存乐观锁扣减）。
 *
 * <p>覆盖 doc/10 §7.E2 / §7.N6 / doc/11 §6.2：</p>
 * <ul>
 *   <li>① 有限库存正常扣减成功（影响行数 1）</li>
 *   <li>② 并发冲突（version 不匹配，影响行数 0）→ 重试后成功</li>
 *   <li>③ 无限库存（stock_remain IS NULL）→ 成功且不耗尽</li>
 *   <li>④ 库存不足（影响行数持续 0）→ 重试 3 次耗尽抛 SKU_OUT_OF_STOCK</li>
 *   <li>⑤ SKU 不存在 → SKU_NOT_FOUND；⑥ qty ≤ 0 → 参数异常</li>
 * </ul>
 */
@Tag("dev")
@DisplayName("GzOrdSkuServiceImpl 单测 — tryDeductStock 乐观锁扣减")
@ExtendWith(MockitoExtension.class)
class GzOrdSkuServiceImplTest {

    @Mock
    private GzOrdSkuMapper skuMapper;

    private GzOrdSkuServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new GzOrdSkuServiceImpl(skuMapper);
    }

    private GzOrdSku sku(Integer stockRemain, int version) {
        GzOrdSku s = new GzOrdSku();
        s.setId(10L);
        s.setProductId(1L);
        s.setStockRemain(stockRemain);
        s.setVersion(version);
        return s;
    }

    @Test
    @DisplayName("① 有限库存正常扣减成功（影响行数 1，一次成功）")
    void deductFiniteStockSuccess() {
        when(skuMapper.selectById(10L)).thenReturn(sku(5, 0));
        when(skuMapper.tryDeductStock(eq(10L), eq(2), eq(0))).thenReturn(1);

        assertDoesNotThrow(() -> service.tryDeductStock(10L, 2));

        verify(skuMapper, times(1)).selectById(10L);
        verify(skuMapper, times(1)).tryDeductStock(10L, 2, 0);
    }

    @Test
    @DisplayName("② version 并发冲突（首次影响行数 0）→ 重查重试后成功")
    void deductRetryOnVersionConflict() {
        // 第一次查 version=0，UPDATE 影响行数 0（被并发改）；第二次查 version=1，UPDATE 成功
        when(skuMapper.selectById(10L)).thenReturn(sku(5, 0), sku(5, 1));
        when(skuMapper.tryDeductStock(eq(10L), eq(1), eq(0))).thenReturn(0);
        when(skuMapper.tryDeductStock(eq(10L), eq(1), eq(1))).thenReturn(1);

        assertDoesNotThrow(() -> service.tryDeductStock(10L, 1));

        verify(skuMapper, times(2)).selectById(10L);
        verify(skuMapper, times(1)).tryDeductStock(10L, 1, 0);
        verify(skuMapper, times(1)).tryDeductStock(10L, 1, 1);
    }

    @Test
    @DisplayName("③ 无限库存（stock_remain IS NULL）→ 扣减成功不耗尽")
    void deductInfiniteStock() {
        // 无限库存：stock_remain = null，SQL 条件恒成立，影响行数 1
        when(skuMapper.selectById(10L)).thenReturn(sku(null, 0));
        when(skuMapper.tryDeductStock(eq(10L), eq(100), eq(0))).thenReturn(1);

        assertDoesNotThrow(() -> service.tryDeductStock(10L, 100));

        verify(skuMapper, times(1)).tryDeductStock(10L, 100, 0);
    }

    @Test
    @DisplayName("④ 库存不足（影响行数持续 0）→ 重试 3 次耗尽抛 SKU_OUT_OF_STOCK")
    void deductExhaustedThrowsOutOfStock() {
        // 每次查到 version=0，但 UPDATE 始终 0（库存 < qty）→ 重试 3 次后抛
        when(skuMapper.selectById(10L)).thenReturn(sku(1, 0));
        when(skuMapper.tryDeductStock(eq(10L), eq(5), anyInt())).thenReturn(0);

        ServiceException ex = assertThrows(ServiceException.class, () -> service.tryDeductStock(10L, 5));
        assertEquals(GzOrdErrorCode.SKU_OUT_OF_STOCK, ex.getCode());

        // 重试上限 3 次：selectById + tryDeductStock 各 3 次
        verify(skuMapper, times(3)).selectById(10L);
        verify(skuMapper, times(3)).tryDeductStock(eq(10L), eq(5), anyInt());
    }

    @Test
    @DisplayName("⑤ SKU 不存在 → SKU_NOT_FOUND")
    void deductSkuNotFound() {
        when(skuMapper.selectById(99L)).thenReturn(null);

        ServiceException ex = assertThrows(ServiceException.class, () -> service.tryDeductStock(99L, 1));
        assertEquals(GzOrdErrorCode.SKU_NOT_FOUND, ex.getCode());
    }

    @Test
    @DisplayName("⑥ qty ≤ 0 → 参数异常（不触 DB）")
    void deductInvalidQty() {
        assertThrows(ServiceException.class, () -> service.tryDeductStock(10L, 0));
        assertThrows(ServiceException.class, () -> service.tryDeductStock(10L, -1));
        verify(skuMapper, times(0)).tryDeductStock(anyLong(), anyInt(), anyInt());
    }
}
