package org.dromara.gz.jp.service.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.dromara.common.core.service.ConfigService;
import org.dromara.gz.jp.domain.entity.*;
import org.dromara.gz.jp.mapper.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@Tag("dev")
class GzJpSnapPurchaseReadTest {
    final GzJpSnapPurchaseMapper tasks=mock(GzJpSnapPurchaseMapper.class);
    final GzJpSnapAccountMapper accounts=mock(GzJpSnapAccountMapper.class);
    final GzJpOrderItemMapper items=mock(GzJpOrderItemMapper.class);
    final ConfigService config=mock(ConfigService.class);
    final GzJpSnapPurchaseReadService service=new GzJpSnapPurchaseReadService(tasks,accounts,items,config,new ObjectMapper());
    @Test void emptyAccountsDoesNotInventHealth() {
        when(accounts.selectList(any())).thenReturn(List.of());
        when(tasks.selectCount(any())).thenReturn(2L);
        var s=service.summary(3);
        assertFalse(s.autoEnabled());assertEquals(0,s.accountCount());assertTrue(s.healthCounts().isEmpty());assertEquals(2,s.manualCount());assertEquals(3,s.reminderCount());
    }
    @Test void readsStoredHealthAndPause() {
        when(accounts.selectList(any())).thenReturn(List.of(GzJpSnapAccount.builder().healthStatus("challenge").build(),GzJpSnapAccount.builder().healthStatus("healthy").build()));
        when(tasks.selectCount(any())).thenReturn(0L);
        when(config.getConfigValue("gz.jp.snap.auto")).thenReturn("{\"enabled\":false,\"pausedReason\":\"challenge\"}");
        var s=service.summary(0);
        assertEquals("challenge",s.pausedReason());assertEquals(1,s.healthCounts().get("healthy"));assertEquals(1,s.healthCounts().get("challenge"));
    }
    @Test void invalidConfigIsVisibleFailure() {
        when(accounts.selectList(any())).thenReturn(List.of());when(config.getConfigValue(any())).thenReturn("broken");
        assertThrows(IllegalStateException.class,()->service.summary(0));
    }
    @Test void shippingDiffUsesRefundRecordRatherThanItemFailureStatus() {
        when(tasks.selectById(4L)).thenReturn(GzJpSnapPurchase.builder().orderItemId(5L).build());
        var i=new GzJpOrderItem();i.setId(5L);i.setProductSnapshotJson("{\"snap\":{\"shippingPayer\":\"buyer\"}}");
        when(items.selectById(5L)).thenReturn(i);when(tasks.shippingRefundStatus(5L)).thenReturn("refunding");when(tasks.shippingRefundAmount(5L)).thenReturn(87L);
        var d=service.detail(4L);assertEquals("buyer",d.shippingPayer());assertEquals("refunding",d.refundStatus());assertEquals(87,d.refundedAmountCent());
    }
    @Test void absentTaskCannotReadOtherItem() {assertThrows(org.dromara.common.core.exception.ServiceException.class,()->service.detail(99L));verifyNoInteractions(items);}
}
