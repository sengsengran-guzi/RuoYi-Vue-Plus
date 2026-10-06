package org.dromara.gz.jp.service.internal;
import org.dromara.gz.jp.domain.entity.GzJpSnapItem;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
@Tag("dev")
class GzJpSnapFilterTest extends Snap302Fixture {
    @Test void searchAndDirectDetailUseSameShopsCategoryKeywordStatusPolicy() {
        var good = item("m1", "975", "c2c", "on_sale", "缶バッジ");
        var badCategory = item("m2", "123", "c2c", "on_sale", "缶バッジ");
        var shops = item("m3", "975", "shops", "on_sale", "缶バッジ");
        var blacklisted = item("m4", "975", "c2c", "on_sale", "専用");
        var sold = item("m5", "975", "c2c", "sold_out", "缶バッジ");
        when(gateway.search(any(), any(), anyInt(), any())).thenReturn(body(good,badCategory,shops,blacklisted,sold));
        assertEquals(1, service.searchInTenant("缶バッジ", "created_desc", 1).items().size());
        for (var item : new com.fasterxml.jackson.databind.JsonNode[]{badCategory,shops,blacklisted,sold}) {
            String id = item.path("external_id").asText(); when(gateway.item(id)).thenReturn(item);
            var detail = service.itemInTenant(id); assertFalse(detail.purchasable()); assertNotNull(detail.unavailableReason());
        }
    }
    @Test void emptyWhitelistRefusesSearchWithoutGatewayAndRefusesDirectDetails() {
        when(config.getConfigValue(any())).thenReturn("{\"categoryWhitelist\":[]}");
        assertTrue(service.searchInTenant("缶バッジ", "created_desc", 1).items().isEmpty());
        verify(gateway,never()).search(any(),any(),anyInt(),any());
        when(gateway.item("m1")).thenReturn(item("m1","975","c2c","on_sale","badge"));
        assertEquals("category_not_allowed", service.itemInTenant("m1").unavailableReason());
    }
    @Test void leafParentOrRootAnyMatchAndNoMissingCategoryPasses() {
        var parentRules = new GzJpSnapFilter.Rules(Set.of("745"), Set.of());
        var child = item("m1","980","c2c","on_sale","badge");
        ((com.fasterxml.jackson.databind.node.ObjectNode)child).putArray("category_lineage").add("980").add("745").add("1328");
        assertNull(filter.reason(child,parentRules));
        assertNull(filter.reason(child,new GzJpSnapFilter.Rules(Set.of("1328"), Set.of())));
        assertNotNull(filter.reason(item("m2","123","c2c","on_sale","badge"),parentRules));
    }
    @Test void freshCacheRechecksNewBlacklistAndImagesNeverUseExternalHosts() {
        when(gateway.search(any(),any(),anyInt(),any())).thenReturn(body(item("m1","975","c2c","on_sale","缶バッジ")));
        var result = service.searchInTenant("缶バッジ","created_desc",1);
        assertEquals("http://127.0.0.1:8700/v1/img/item/synthetic.png",result.items().get(0).thumbUrl());
        when(config.getConfigValue(any())).thenReturn("{\"categoryWhitelist\":[\"745\",\"975\",\"980\"],\"keywordBlacklist\":[\"缶バッジ\"]}");
        assertTrue(service.searchInTenant("缶バッジ","created_desc",1).items().isEmpty());
        verify(gateway,times(1)).search(any(),any(),anyInt(),any());
        assertEquals("",service.image("https://evil.test/a.png"));
    }
    @Test void detailsAreFreshUpsertSnapshotButOutboundNeverContainsRawOrSeller() throws Exception {
        var live = item("m1","975","c2c","on_sale","badge"); ((com.fasterxml.jackson.databind.node.ObjectNode)live).put("private_key","must_not_leak");
        when(gateway.item("m1")).thenReturn(live);
        var first = service.itemInTenant("m1"); service.itemInTenant("m1");
        verify(gateway,times(2)).item("m1");
        var capture = ArgumentCaptor.forClass(GzJpSnapItem.class); verify(items,times(2)).upsertLive(capture.capture());
        assertNotNull(capture.getValue().getFetchedAt()); assertTrue(capture.getValue().getRawJson().contains("must_not_leak"));
        String response = json.writeValueAsString(first); assertFalse(response.contains("must_not_leak")); assertFalse(response.contains("rawJson")); assertFalse(response.contains("sellerId"));
        assertNull(capture.getValue().getActiveOrderItemId());
    }
}
