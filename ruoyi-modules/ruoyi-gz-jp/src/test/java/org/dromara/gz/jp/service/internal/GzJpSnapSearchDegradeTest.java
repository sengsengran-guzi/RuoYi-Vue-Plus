package org.dromara.gz.jp.service.internal;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.gz.jp.snap.gateway.GzJpGatewayException;
import org.dromara.gz.jp.controller.applet.GzJpSnapMpController;
import org.dromara.common.redis.utils.RedisUtils;
import org.dromara.common.ratelimiter.annotation.RateLimiter;
import org.junit.jupiter.api.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
@Tag("dev")
class GzJpSnapSearchDegradeTest extends Snap302Fixture {
    GzJpGatewayException offline() { return new GzJpGatewayException(GzJpGatewayException.Kind.TIMEOUT,0,"offline",null); }
    @Test void timeoutWithoutCacheHasExplicitBusinessCode() {
        when(gateway.search(any(),any(),anyInt(),any())).thenThrow(offline());
        assertEquals(4119, assertThrows(ServiceException.class, () -> service.searchInTenant("缶バッジ","created_desc",1)).getCode());
    }
    @Test void freshCacheAvoidsGatewayAndExpiredCacheFallsBackMarkedStale() {
        when(gateway.search(any(),any(),anyInt(),any())).thenReturn(body(item("m1","975","c2c","on_sale","badge")));
        assertFalse(service.searchInTenant("缶バッジ","created_desc",1).stale());
        service.searchInTenant("缶バッジ","created_desc",1); verify(gateway,times(1)).search(any(),any(),anyInt(),any());
        cache.values.replaceAll((key,value) -> key.startsWith("search:") ? value.replaceFirst("\"at\":[0-9]+", "\"at\":1") : value);
        when(gateway.search(any(),any(),anyInt(),any())).thenThrow(offline());
        var stale = service.searchInTenant("缶バッジ","created_desc",1); assertTrue(stale.stale()); assertEquals(1,stale.items().size());
    }
    @Test void tenConcurrentQueriesMergeIntoOneCallAcrossThreeIndependentRounds() throws Exception {
        when(gateway.search(any(),any(),anyInt(),any())).thenAnswer(call -> { Thread.sleep(120); return body(item("m1","975","c2c","on_sale","badge")); });
        var executor = Executors.newFixedThreadPool(10);
        try {
            for (int round=0; round<3; round++) {
                String q = "缶バッジ " + round; var start = new CountDownLatch(1); var jobs = new ArrayList<Future<?>>();
                for (int i=0;i<10;i++) jobs.add(executor.submit(() -> { start.await(); assertEquals(1,service.searchInTenant(q,"created_desc",1).items().size()); return null; }));
                start.countDown(); for (var job : jobs) job.get(5,TimeUnit.SECONDS);
            }
        }
        finally { executor.shutdownNow(); }
        verify(gateway,times(3)).search(any(),any(),anyInt(),any());
    }
    @Test void rateExhaustionBeforeDetailNeverCallsGatewayAndAnnotationGuardsSearch() throws Exception {
        var identity = spy(new GzJpSnapRateIdentity()); doReturn("ip:127.0.0.1").when(identity).key();
        doReturn(-1L).when(identity).acquire();
        var controller = new GzJpSnapMpController(service,identity);
        assertEquals(4118,assertThrows(ServiceException.class,()->controller.item("m1")).getCode());
        verifyNoInteractions(gateway);
        var annotation = GzJpSnapMpController.class.getMethod("search",String.class,String.class,int.class).getAnnotation(RateLimiter.class);
        assertEquals("SNAP_TOO_FAST",annotation.message()); assertTrue(annotation.key().contains("gzJpSnapRateIdentity"));
    }
}
