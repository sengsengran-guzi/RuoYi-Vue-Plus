package org.dromara.gz.jp.service.internal;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.dromara.gz.jp.domain.vo.*;
import org.junit.jupiter.api.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
@Tag("dev")
class GzJpSnapDetailContractTest {
    @Test void publicDetailPriceNeverExposesRateOrMarkup() throws Exception {
        var price=new GzJpSnapPriceVO(3760,200,900,4860,801,"quote",600);
        var detail=new GzJpSnapDetailVO("m1","ja","zh","ja","zh",801,"on_sale","buyer","new","745",List.of(),true,null,4860L,"manual","buyer_shipping",price,null);
        var n=new ObjectMapper().readTree(new ObjectMapper().writeValueAsString(detail));
        assertEquals(4860,n.path("price").path("totalCent").asLong());
        assertFalse(n.toString().matches("(?is).*?(rate|markup).*"));
        assertEquals(n.path("price").path("totalCent").asLong(),n.path("price").path("goodsCent").asLong()+n.path("price").path("fixedFeeCent").asLong()+n.path("price").path("estShippingCent").asLong());
    }
    @Test void liveDetailSellerRefusalPersistsBlockedAndNoQuote() {
        var f=new Snap302Fixture();
        var live=(com.fasterxml.jackson.databind.node.ObjectNode)f.item("m1","745","item","on_sale","アニメ");live.put("description_ja","即購入不可");
        org.mockito.Mockito.when(f.gateway.item("m1")).thenReturn(live);
        org.mockito.Mockito.when(f.gateway.seller("99000001")).thenReturn(f.node("{\"seller_id\":\"99000001\",\"intro\":\"代行業者NG\"}"));
        var v=f.service.itemInTenant("m1"); assertEquals("blocked",v.gateResult());assertFalse(v.purchasable());assertNull(v.price());
        var captor=org.mockito.ArgumentCaptor.forClass(org.dromara.gz.jp.domain.entity.GzJpSnapItem.class);
        org.mockito.Mockito.verify(f.items).upsertLive(captor.capture());assertEquals("proxy_refuse:代行業者@seller_intro",captor.getValue().getGateReason());
        f.service.itemInTenant("m1");org.mockito.Mockito.verify(f.gateway,org.mockito.Mockito.times(1)).seller("99000001");
        org.mockito.Mockito.verify(f.quotes,org.mockito.Mockito.never()).quote(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString());
    }
    @Test void missingSellerFallsBackToLiveItemAdmission() {
        var f=new Snap302Fixture();var live=f.item("m1","745","item","on_sale","アニメ");
        org.mockito.Mockito.when(f.gateway.item("m1")).thenReturn(live);
        var v=f.service.itemInTenant("m1");assertEquals("auto",v.gateResult());assertTrue(v.purchasable());assertNotNull(v.price());
        org.mockito.Mockito.verify(f.gateway).item("m1");
    }
    @Test void unconfiguredPriceReturnsRefusalWithoutLosingDetail() {
        var f=new Snap302Fixture(); org.mockito.Mockito.when(f.gateway.item("m1")).thenReturn(f.item("m1","745","item","on_sale","アニメ"));
        org.mockito.Mockito.when(f.quotes.quote(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString())).thenThrow(new org.dromara.common.core.exception.ServiceException("unset",4121));
        var v=f.service.itemInTenant("m1");assertEquals(4121,v.quoteErrorCode());assertEquals("auto",v.gateResult());assertFalse(v.purchasable());assertNull(v.price());
    }
    @Test void listHasNoQuoteOrPrivateTerms() throws Exception {
        var v=new GzJpSnapSearchVO("q",null,List.of(new GzJpSnapSearchVO.Card("m1","ja","zh",801,3960L,"")),false,false);
        String raw=new ObjectMapper().writeValueAsString(v);assertFalse(raw.matches("(?is).*?(rate|markup|quoteId).*"));assertTrue(raw.contains("3960"));
    }
}
