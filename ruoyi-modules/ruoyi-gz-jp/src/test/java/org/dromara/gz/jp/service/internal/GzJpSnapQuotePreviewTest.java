package org.dromara.gz.jp.service.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.dromara.gz.jp.domain.dto.GzJpSnapQuoteSnapshot;
import org.dromara.gz.jp.domain.entity.GzJpSnapItem;
import org.dromara.gz.jp.mapper.GzJpSnapItemMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("dev")
class GzJpSnapQuotePreviewTest {
    @Test void projectsOriginalTermsWithoutPrivatePricingOrRefresh() throws Exception {
        var quotes = mock(GzJpSnapQuoteService.class);
        var items = mock(GzJpSnapItemMapper.class);
        var quote = new GzJpSnapQuoteSnapshot("m123", 1000, new BigDecimal("4.2"), new BigDecimal("0.1"),
            4620, 200, 900, 5720, 100, 60100, "manual", "buyer");
        when(quotes.require("original")).thenReturn(quote);
        when(items.selectOne(any())).thenReturn(GzJpSnapItem.builder().titleZh("测试商品").titleJa("商品").build());
        var service = new GzJpSnapQuotePreviewService(quotes, items);
        var first = service.preview("original");
        assertEquals(first, service.preview("original"));
        assertEquals("original", first.price().quoteId());
        assertEquals(60100, first.price().expiresAt());
        assertEquals(5720, first.price().totalCent());
        var tree = new ObjectMapper().readTree(new ObjectMapper().writeValueAsString(first));
        assertEquals(4, tree.size());
        assertEquals(7, tree.path("price").size());
        assertFalse(tree.toString().contains("ratePer100jpy"));
        assertFalse(tree.toString().contains("markupRatio"));
        verify(quotes, times(2)).require("original");
        verifyNoMoreInteractions(quotes);
    }
    @Test void missingSnapshotRejectsCheckout() {
        var quotes = mock(GzJpSnapQuoteService.class);
        var items = mock(GzJpSnapItemMapper.class);
        when(quotes.require("missing")).thenThrow(new org.dromara.common.core.exception.ServiceException("过期", 4123));
        assertThrows(org.dromara.common.core.exception.ServiceException.class,
            () -> new GzJpSnapQuotePreviewService(quotes, items).preview("missing"));
        verifyNoInteractions(items);
    }
}
