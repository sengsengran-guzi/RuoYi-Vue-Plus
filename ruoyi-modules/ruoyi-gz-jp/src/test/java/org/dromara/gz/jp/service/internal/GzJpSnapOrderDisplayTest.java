package org.dromara.gz.jp.service.internal;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.dromara.gz.jp.domain.entity.GzJpOrderItem;
import org.dromara.gz.jp.domain.entity.GzJpRefund;
import org.dromara.gz.jp.domain.entity.GzJpSnapPurchase;
import org.dromara.gz.jp.domain.vo.GzJpOrderItemVO;
import org.dromara.gz.jp.mapper.GzJpRefundMapper;
import org.dromara.gz.jp.mapper.GzJpSnapPurchaseMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@Tag("dev")
class GzJpSnapOrderDisplayTest {
    @Test void procurementStatesArePublicAndTradeFailuresStayDistinct() {
        var task = new GzJpSnapPurchase();
        task.setTradeStatus("none");
        for (String status : List.of("queued", "executing", "unknown")) {
            task.setPurchaseStatus(status);
            assertEquals("purchasing", GzJpSnapOrderDisplayService.project(task, 0).status());
        }
        task.setPurchaseStatus("manual_pending");
        assertEquals("manual_processing", GzJpSnapOrderDisplayService.project(task, 0).status());
        task.setPurchaseStatus("success");
        assertEquals("purchased", GzJpSnapOrderDisplayService.project(task, 0).status());
        task.setPurchaseStatus("failed");
        assertEquals("not_purchased", GzJpSnapOrderDisplayService.project(task, 0).status());
        task.setTradeStatus("cancelled");
        assertEquals("trade_cancelled", GzJpSnapOrderDisplayService.project(task, 0).status());
        task.setTradeStatus("returned");
        assertEquals("inspection_failed", GzJpSnapOrderDisplayService.project(task, 0).status());
        task.setTradeStatus("private new enum");
        assertEquals("none", GzJpSnapOrderDisplayService.project(task, 0).trade());
    }
    @Test void batchDoesNotQuerySnapAndPublicDtoCannotExposeTaskInternals() throws Exception {
        var tasks = mock(GzJpSnapPurchaseMapper.class);
        var refunds = mock(GzJpRefundMapper.class);
        var batch = new GzJpOrderItem(); batch.setSource("batch"); batch.setId(1L);
        assertTrue(new GzJpSnapOrderDisplayService(tasks, refunds).load(List.of(batch)).isEmpty());
        verifyNoInteractions(tasks, refunds);
        var dto = new GzJpOrderItemVO(); dto.setSnapStatus("purchasing"); dto.setSnapTradeStatus("none"); dto.setShippingDiffRefundedCent(0L);
        String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(dto);
        assertFalse(json.matches("(?s).*(purchaseStatus|accountCode|lastError|ratePer100jpy|markupRatio|evidenceFileIds).*"));
    }
    @Test void multipleSnapRowsUseExactlyTwoQueriesAndShippingSumIsPerItem() {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
            new org.apache.ibatis.builder.MapperBuilderAssistant(new com.baomidou.mybatisplus.core.MybatisConfiguration(), "504"), GzJpRefund.class);
        var tasks = mock(GzJpSnapPurchaseMapper.class);
        var refunds = mock(GzJpRefundMapper.class);
        when(tasks.selectList(any(Wrapper.class))).thenReturn(List.of());
        var refund1 = new GzJpRefund(); refund1.setOrderItemId(1L); refund1.setRefundAmountCent(100L);
        var refund2 = new GzJpRefund(); refund2.setOrderItemId(1L); refund2.setRefundAmountCent(23L);
        when(refunds.selectList(any(Wrapper.class))).thenReturn(List.of(refund1, refund2));
        var one = new GzJpOrderItem(); one.setSource("snap"); one.setId(1L);
        var two = new GzJpOrderItem(); two.setSource("snap"); two.setId(2L);
        var result = new GzJpSnapOrderDisplayService(tasks, refunds).load(List.of(one, two));
        assertEquals(123L, result.get(1L).shippingRefunded());
        assertEquals(0L, result.get(2L).shippingRefunded());
        verify(tasks, times(1)).selectList(any(Wrapper.class));
        var query = org.mockito.ArgumentCaptor.forClass(Wrapper.class);
        verify(refunds, times(1)).selectList(query.capture());
        assertTrue(query.getValue().getSqlSegment().contains("refund_type"));
        assertTrue(query.getValue().getSqlSegment().contains("status"));
        var parameters = ((com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<?>) query.getValue()).getParamNameValuePairs();
        assertTrue(parameters.containsValue("shipping_diff"));
        assertTrue(parameters.containsValue("refunded"));
    }
}
