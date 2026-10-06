package org.dromara.gz.jp.service.internal;
import org.dromara.gz.jp.domain.entity.GzJpSnapItem;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
@Tag("dev")
class GzJpSnapSearchSnapshotTest extends Snap302Fixture {
    @Test void knownGateHintsAreBatchedAndUnknownIsNotInvented() {
        when(gateway.search(any(), any(), anyInt(), any())).thenReturn(body(
            item("m1", "975", "c2c", "on_sale", "badge"), item("m2", "975", "c2c", "on_sale", "badge")));
        when(items.selectList(any())).thenReturn(List.of(GzJpSnapItem.builder()
            .externalId("m1").gateResult("manual").gateReason("comment_required").build()));
        var result = service.searchInTenant("缶バッジ", "created_desc", 1);
        assertEquals("manual", result.items().get(0).gateResult());
        assertEquals("comment_required", result.items().get(0).gateReason());
        assertNull(result.items().get(1).gateResult());
        assertNull(result.items().get(1).gateReason());
        verify(items, times(1)).selectList(any());
        verify(items, never()).upsertLive(any());
        verify(gateway, never()).item(any());
    }
}
