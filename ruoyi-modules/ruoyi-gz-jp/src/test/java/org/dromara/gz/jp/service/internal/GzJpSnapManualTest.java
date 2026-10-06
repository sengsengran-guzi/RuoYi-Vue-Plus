package org.dromara.gz.jp.service.internal;
import org.dromara.gz.jp.domain.entity.*;
import org.dromara.gz.jp.service.internal.dto.GzJpRefundPlan;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
@Tag("dev")
class GzJpSnapManualTest {
 private Snap305Fixture fixture(){var f=new Snap305Fixture();f.task.setPurchaseStatus("manual_pending");f.task.setLockedPriceJpy(1000L);when(f.tasks.selectById(1L)).thenReturn(f.task);var o=new GzJpOrder();o.setBusinessStatus("paid");when(f.orders.lockById(4L)).thenReturn(o);when(f.items.advanceGuarded(anyList(),eq("purchasing"),eq("await_seller_ship"),eq(7L))).thenReturn(1);return f;}
 @Test void successKeepsShippingUnknownAndRecordsOperator(){var f=fixture();f.tx.manual(1L,"txn",900L,null,null,7L);assertEquals("success",f.task.getPurchaseStatus());assertEquals("await_ship",f.task.getTradeStatus());assertEquals(7L,f.task.getManualOperator());assertNull(f.task.getActualShippingJpy());verify(f.tasks).saveOutcome(f.task);}
 @Test void refusesPriceUpAndMissingTxn(){var f=fixture();var e=assertThrows(org.dromara.common.core.exception.ServiceException.class,()->f.tx.manual(1L,"txn",1001L,null,null,7L));assertTrue(e.getMessage().contains("SNAP_MANUAL_PRICE_UP"));assertThrows(org.dromara.common.core.exception.ServiceException.class,()->f.tx.manual(1L,"",900L,null,null,7L));verify(f.tasks,never()).saveOutcome(any());}
 @Test void rejectsEveryOtherState(){for(String state:java.util.List.of("queued","executing","unknown","success","failed")){var f=fixture();f.task.setPurchaseStatus(state);assertThrows(org.dromara.common.core.exception.ServiceException.class,()->f.tx.manual(1L,null,null,"manual_rejected","note",7L));verify(f.refunds,never()).markFailedAndCreateRefunds(anyList(),anyString(),any(),anyString());}}
 @Test void failureUsesSameRefundAndLockRelease(){var f=fixture();var plan=new GzJpRefundPlan();when(f.refunds.markFailedAndCreateRefunds(anyList(),anyString(),eq(7L),eq("snap_manual"))).thenReturn(plan);assertSame(plan,f.tx.manual(1L,null,null,"manual_rejected","note",7L));assertEquals("failed",f.task.getPurchaseStatus());assertEquals("note",f.task.getManualNote());verify(f.snaps).releaseSnapLock(2L);}
}
