package org.dromara.gz.jp.service.internal;

import org.dromara.gz.jp.domain.entity.GzJpOrder;
import org.dromara.gz.jp.mapper.GzJpOrderMapper;
import org.dromara.gz.jp.mapper.GzJpOrderItemMapper;
import org.dromara.gz.jp.service.impl.GzJpOrderServiceImpl;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("dev")
class GzJpSnapOrderOwnershipTest {
    @Test void anotherCustomerAndStaffIdCannotReadOrReachSnapProjection() {
        var orders = mock(GzJpOrderMapper.class);
        var items = mock(GzJpOrderItemMapper.class);
        var projection = mock(GzJpSnapOrderDisplayService.class);
        var order = new GzJpOrder(); order.setId(504L); order.setUserId(14L);
        when(orders.selectById(504L)).thenReturn(order);
        var service = new GzJpOrderServiceImpl(orders, items, null, null, null, null, null, null, null, null, null, null, null, null, projection);
        assertNull(service.getDetail(504L, 15L));
        assertNull(service.getDetail(504L, 1L));
        assertNull(service.getDetail(504L, null));
        assertNull(service.getDetail(999L, 14L));
        verifyNoInteractions(items, projection);
    }
}
