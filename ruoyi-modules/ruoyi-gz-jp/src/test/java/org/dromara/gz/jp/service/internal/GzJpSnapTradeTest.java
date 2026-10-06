package org.dromara.gz.jp.service.internal;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.gz.jp.service.internal.dto.GzJpRefundPlan;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("dev") class GzJpSnapTradeTest {
 private Snap305Fixture f;
 private GzJpSnapTradeTx trade;
 @BeforeEach void init(){
  f=new Snap305Fixture(); f.task.setPurchaseStatus("success"); f.task.setTradeStatus("await_ship");
  when(f.tasks.selectById(1L)).thenReturn(f.task); when(f.tasks.saveTrade(any())).thenReturn(1);
  var item=f.items.selectById(2L); item.setSource("snap"); item.setFulfillStatus("await_seller_ship");
  item.setProductSnapshotJson("{\"snap\":{\"shippingPayer\":\"buyer\"}}");
  when(f.config.getConfigValue("gz.jp.snap.trade")).thenReturn("{\"rateDeadlineHours\":30,\"patrolMinutes\":12}");
  trade=new GzJpSnapTradeTx(f.tasks,f.snaps,f.orders,f.items,f.tx,f.refunds,f.config,f.json);
 }
 @Test void sequentialArrivalAndRatingCreateDiffWithoutAdvancingFulfill(){
  trade.transition(1L,"shipped",null,5L);assertThrows(ServiceException.class,()->trade.transition(1L,"rated",null,5L));
  var p=new GzJpRefundPlan();when(f.refunds.createShippingDiff(2L,100L)).thenReturn(p);
  assertSame(p,trade.transition(1L,"arrived",100L,5L));assertEquals(100,f.task.getActualShippingJpy());
  assertEquals(f.task.getArrivedAt().plusHours(30),f.task.getRateDeadlineAt());assertEquals("await_seller_ship",f.items.selectById(2L).getFulfillStatus());
  trade.transition(1L,"rated",null,5L);assertNotNull(f.task.getRatedAt());assertEquals("success",f.task.getPurchaseStatus());
  verify(f.items,never()).advanceGuarded(anyList(),anyString(),anyString(),any());
 }
 @Test void buyerArrivalRequiresNonnegativeShipping(){f.task.setTradeStatus("shipped");assertThrows(ServiceException.class,()->trade.transition(1L,"arrived",null,1L));assertThrows(ServiceException.class,()->trade.transition(1L,"arrived",-1L,1L));verify(f.tasks,never()).saveTrade(any());verifyNoInteractions(f.refunds);}
 @Test void freeShippingRemainsNull(){f.task.setTradeStatus("shipped");f.items.selectById(2L).setProductSnapshotJson("{\"snap\":{\"shippingPayer\":\"seller\"}}");assertThrows(ServiceException.class,()->trade.transition(1L,"arrived",100L,1L));trade.transition(1L,"arrived",null,1L);assertNull(f.task.getActualShippingJpy());verifyNoInteractions(f.refunds);}
 @Test void cannotSkipOrGoBack(){for(var target:List.of("arrived","rated","returned","none"))assertThrows(ServiceException.class,()->trade.transition(1L,target,null,1L));f.task.setTradeStatus("rated");for(var target:List.of("shipped","arrived","returned","cancelled"))assertThrows(ServiceException.class,()->trade.transition(1L,target,null,1L));verify(f.tasks,never()).saveTrade(any());}
 @Test void cancellationFromAwaitOrShippedUsesInternalFailureAndIsIdempotent(){for(var from:List.of("await_ship","shipped")){init();f.task.setTradeStatus(from);var p=new GzJpRefundPlan();when(f.refunds.markFailedAndCreateRefunds(anyList(),anyString(),eq(1L),eq("snap_trade"))).thenReturn(p);assertSame(p,trade.transition(1L,"cancelled",null,1L));assertEquals("seller_cancel",f.task.getFailReason());assertEquals("success",f.task.getPurchaseStatus());assertNull(trade.transition(1L,"cancelled",null,1L));verify(f.refunds,times(1)).markFailedAndCreateRefunds(anyList(),anyString(),eq(1L),eq("snap_trade"));verify(f.snaps,times(1)).releaseSnapLock(2L);}}
 @Test void arrivedCannotCancelAndReturnUsesInspectReason(){f.task.setTradeStatus("arrived");assertThrows(ServiceException.class,()->trade.transition(1L,"cancelled",null,1L));var p=new GzJpRefundPlan();when(f.refunds.markFailedAndCreateRefunds(anyList(),anyString(),eq(1L),eq("snap_trade"))).thenReturn(p);assertSame(p,trade.transition(1L,"returned",null,1L));assertEquals("inspect_ng",f.task.getFailReason());verify(f.snaps).releaseSnapLock(2L);}
 @Test void guardedWriteMustWinBeforeRefundOrRelease(){when(f.tasks.saveTrade(any())).thenReturn(0);assertThrows(ServiceException.class,()->trade.transition(1L,"cancelled",null,1L));verifyNoInteractions(f.refunds);verify(f.snaps,never()).releaseSnapLock(anyLong());}
 @Test void lockOrderIsCurrentReadAndConsistent(){trade.transition(1L,"shipped",null,1L);var o=inOrder(f.snaps,f.orders,f.items,f.tasks);o.verify(f.tasks).selectById(1L);o.verify(f.snaps).lockSnapById(3L);o.verify(f.items).selectById(2L);o.verify(f.orders).lockById(4L);o.verify(f.items).lockById(2L);o.verify(f.tasks).lock(1L);o.verify(f.tasks).saveTrade(any());}
}
