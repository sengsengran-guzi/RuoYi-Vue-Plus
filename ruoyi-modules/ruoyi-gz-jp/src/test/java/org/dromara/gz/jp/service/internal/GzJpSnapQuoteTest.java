package org.dromara.gz.jp.service.internal;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.service.ConfigService;
import org.dromara.gz.jp.mapper.GzJpFxRateMapper;
import org.dromara.gz.jp.domain.entity.GzJpFxRate;
import org.junit.jupiter.api.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
@Tag("dev")
class GzJpSnapQuoteTest {
    final ConfigService config=mock(ConfigService.class);
    final GzJpFxRateMapper rates=mock(GzJpFxRateMapper.class);
    final GzJpSnapQuoteStore store=mock(GzJpSnapQuoteStore.class);
    final ObjectMapper json=new ObjectMapper();
    final GzJpSnapQuoteService service=new GzJpSnapQuoteService(config,rates,store,json);
    final GzJpSnapQuoteService.Pricing pricing=new GzJpSnapQuoteService.Pricing(new BigDecimal("0.1"),200,900,600);
    @BeforeEach void setup(){when(config.getConfigValue("gz.jp.snap.pricing")).thenReturn("{\"markupRatio\":0.1,\"fixedFeeCent\":200,\"estShippingCent\":900,\"quoteTtlSec\":600}"); when(rates.selectOne(any())).thenReturn(GzJpFxRate.builder().ratePer100jpy(new BigDecimal("4.2673")).fetchedAt(LocalDateTime.now()).build());}
    @Test void ceilAndFeeConservation(){var v=service.calculate("m1",801,"manual","buyer",pricing,new BigDecimal("4.2673"),0);assertEquals(3760,v.goodsCent());assertEquals(4860,v.totalCent());assertEquals(v.goodsCent()+v.fixedFeeCent()+v.estShippingCent(),v.totalCent());}
    @Test void shippingNeverMarkedUp(){var a=service.calculate("m1",801,"auto","seller",pricing,new BigDecimal("4.2673"),0);var b=service.calculate("m1",801,"manual","buyer",pricing,new BigDecimal("4.2673"),0);assertEquals(900,b.totalCent()-a.totalCent());}
    @Test void manualCommentNoShipping(){assertEquals(0,service.calculate("m1",801,"manual","seller",pricing,new BigDecimal("4.2673"),0).estShippingCent());}
    @Test void eachUnsetRejected() throws Exception {for(String field:java.util.List.of("markupRatio","fixedFeeCent","estShippingCent")){var n=json.readTree(config.getConfigValue("gz.jp.snap.pricing"));((com.fasterxml.jackson.databind.node.ObjectNode)n).putNull(field);when(config.getConfigValue("gz.jp.snap.pricing")).thenReturn(n.toString());assertEquals(4121,assertThrows(ServiceException.class,service::pricing).getCode());when(config.getConfigValue("gz.jp.snap.pricing")).thenReturn("{\"markupRatio\":0.1,\"fixedFeeCent\":200,\"estShippingCent\":900}");}}
    @Test void staleAndMissingRejected(){when(rates.selectOne(any())).thenReturn(GzJpFxRate.builder().ratePer100jpy(BigDecimal.ONE).fetchedAt(LocalDateTime.now().minusHours(7)).build());assertEquals(4122,assertThrows(ServiceException.class,service::latestFresh).getCode());when(rates.selectOne(any())).thenReturn(null);assertThrows(ServiceException.class,service::latestFresh);}
    @Test void quoteStoredWithTtl(){var v=service.quote("m1",801,"auto","seller");verify(store).put(eq(v.quoteId()),contains("\"ratePer100jpy\""),eq(java.time.Duration.ofSeconds(600)));assertEquals(3960,v.totalCent());}
    @Test void expiredMissingAndWrongItem() throws Exception {assertEquals(4123,assertThrows(ServiceException.class,()->service.require("missing","m1")).getCode());String id="aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";var v=service.calculate("m1",801,"auto","seller",pricing,BigDecimal.ONE,System.currentTimeMillis());when(store.get(id)).thenReturn(json.writeValueAsString(v));assertEquals(4124,assertThrows(ServiceException.class,()->service.require(id,"m2")).getCode());assertEquals(v,service.require(id,"m1"));when(store.get(id)).thenReturn(json.writeValueAsString(service.calculate("m1",801,"auto","seller",pricing,BigDecimal.ONE,0)));assertEquals(4123,assertThrows(ServiceException.class,()->service.require(id,"m1")).getCode());}
    @Test void referenceHasNoRedisQuote(){assertEquals(3960,service.reference(801,"seller"));verifyNoInteractions(store);}
    @Test void summaryWithoutPayerStillHasReference(){assertEquals(3960,service.reference(801,""));verifyNoInteractions(store);}
    @Test void malformedAndNegativeRejected(){when(config.getConfigValue("gz.jp.snap.pricing")).thenReturn("{bad");assertThrows(ServiceException.class,service::pricing);when(config.getConfigValue("gz.jp.snap.pricing")).thenReturn("{\"markupRatio\":-1,\"fixedFeeCent\":0,\"estShippingCent\":0}");assertThrows(ServiceException.class,service::pricing);}
    @Test void bocHeaderOrderAndCurrency(){var fx=new GzJpSnapFxService(rates);assertEquals(new BigDecimal("4.2673"),fx.parse("<table><tr><th>现钞卖出价</th><th>货币名称</th><th>现汇卖出价</th></tr><tr><td>999</td><td>日元</td><td>4.2673</td></tr></table>"));assertThrows(IllegalArgumentException.class,()->fx.parse("<table><tr><td>日元</td><td>4</td></tr></table>"));}
    @Test void manualAppendPreservesSource(){var fx=new GzJpSnapFxService(rates);var v=fx.manual(new BigDecimal("4.2"));assertEquals("manual",v.getSource());verify(rates).insert(v);assertThrows(IllegalArgumentException.class,()->fx.manual(BigDecimal.ZERO));}
}
