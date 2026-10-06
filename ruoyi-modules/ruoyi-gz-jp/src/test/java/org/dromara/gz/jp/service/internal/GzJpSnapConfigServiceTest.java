package org.dromara.gz.jp.service.internal;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.dromara.gz.jp.mapper.GzJpSnapRuntimeConfigMapper;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.tenant.helper.TenantHelper;
import org.junit.jupiter.api.*;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.cache.transaction.TransactionAwareCacheManagerProxy;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
@Tag("dev")
class GzJpSnapConfigServiceTest {
 final ObjectMapper json=new ObjectMapper();
 GzJpSnapRuntimeConfigMapper mapper;GzJpSnapConfigService service;ConcurrentMapCacheManager caches;
 @BeforeEach void setup(){mapper=mock(GzJpSnapRuntimeConfigMapper.class);caches=new ConcurrentMapCacheManager("sys_config");service=new GzJpSnapConfigService(mapper,json,new TransactionAwareCacheManagerProxy(caches));}
 ObjectNode pricing() throws Exception{return (ObjectNode)json.readTree("{\"markupRatio\":null,\"fixedFeeCent\":null,\"estShippingCent\":null,\"quoteTtlSec\":600}");}
 @Test void unconfiguredPricesRemainNull() throws Exception {assertEquals(pricing(),service.normalize("pricing",pricing()));}
 @Test void ratioBounds() throws Exception{for(double n:new double[]{-0.01,1.01}){var p=pricing();p.put("markupRatio",n);assertThrows(ServiceException.class,()->service.normalize("pricing",p));}for(int n:new int[]{0,1}){var p=pricing();p.put("markupRatio",n);assertEquals(n,service.normalize("pricing",p).path("markupRatio").asInt());}}
 @Test void centsMustBeNonNegativeIntegers() throws Exception{for(String f:new String[]{"fixedFeeCent","estShippingCent"}){var p=pricing();p.put(f,-1);assertThrows(ServiceException.class,()->service.normalize("pricing",p));p.put(f,1.5);assertThrows(ServiceException.class,()->service.normalize("pricing",p));p.put(f,0);assertEquals(0,service.normalize("pricing",p).path(f).asInt());}}
 @Test void quoteTtlBoundaries() throws Exception{for(int n:new int[]{59,1801}){var p=pricing();p.put("quoteTtlSec",n);assertThrows(ServiceException.class,()->service.normalize("pricing",p));}for(int n:new int[]{60,1800}){var p=pricing();p.put("quoteTtlSec",n);assertEquals(n,service.normalize("pricing",p).path("quoteTtlSec").asInt());}}
 @Test void tradeBoundaries() throws Exception{var p=(ObjectNode)json.readTree("{\"rateDeadlineHours\":1,\"patrolMinutes\":5}");assertEquals(p,service.normalize("trade",p));p.put("rateDeadlineHours",240);p.put("patrolMinutes",120);assertEquals(p,service.normalize("trade",p));for(String f:new String[]{"rateDeadlineHours","patrolMinutes"})for(int n:new int[]{0,241}){var bad=p.deepCopy();bad.put(f,n);assertThrows(ServiceException.class,()->service.normalize("trade",bad));}}
 @Test void listsTrimDedupeAndRetainGateContract() throws Exception{var p=json.readTree("{\"blockKeywords\":[\" 専用 \",\"専用\",\" \",\"\"],\"proxyRefuseKeywords\":[],\"manualKeywords\":[\"コメント必須\"]}");var out=service.normalize("gate",p);assertEquals(1,out.path("blockKeywords").size());assertEquals("専用",out.path("blockKeywords").get(0).asText());assertTrue(out.has("proxyRefuseKeywords"));assertTrue(out.has("manualKeywords"));}
 @Test void unknownAutoMissingAndExtraFieldsRejected() throws Exception{assertThrows(ServiceException.class,()->service.read("auto"));var p=pricing();p.put("enabled",true);assertThrows(ServiceException.class,()->service.normalize("pricing",p));p.remove("enabled");p.remove("fixedFeeCent");assertThrows(ServiceException.class,()->service.normalize("pricing",p));verifyNoInteractions(mapper);}
 @Test void malformedListsRejected() throws Exception{for(String raw:new String[]{"{\"categoryWhitelist\":[1],\"keywordBlacklist\":[]}","{\"categoryWhitelist\":null,\"keywordBlacklist\":[]}"})assertThrows(ServiceException.class,()->service.normalize("filter",json.readTree(raw)));}
 @Test void cacheIsEvictedOnlyAfterCommitThroughTransactionDecorator() throws Exception{
  when(mapper.write(eq("gz.jp.snap.pricing"),anyString(),eq(1L))).thenReturn(1);
  caches.getCache("sys_config").put("gz.jp.snap.pricing","old");
  try(var tenant=mockStatic(TenantHelper.class)){
   tenant.when(()->TenantHelper.dynamic(eq("1001"),any(java.util.function.Supplier.class))).thenAnswer(i->((java.util.function.Supplier<?>)i.getArgument(1)).get());
   TransactionSynchronizationManager.initSynchronization();
   try{service.save("pricing",pricing(),1L);assertNotNull(caches.getCache("sys_config").get("gz.jp.snap.pricing"));
    var callbacks=TransactionSynchronizationManager.getSynchronizations();callbacks.forEach(s->s.afterCommit());assertNull(caches.getCache("sys_config").get("gz.jp.snap.pricing"));assertEquals(1,TransactionSynchronizationManager.getSynchronizations().size());
   }finally{TransactionSynchronizationManager.clearSynchronization();}
  }
 }
 @Test void missingRowDoesNotScheduleEviction() throws Exception {TransactionSynchronizationManager.initSynchronization();try{assertThrows(ServiceException.class,()->service.save("pricing",pricing(),1L));assertTrue(TransactionSynchronizationManager.getSynchronizations().isEmpty());}finally{TransactionSynchronizationManager.clearSynchronization();}}
}
