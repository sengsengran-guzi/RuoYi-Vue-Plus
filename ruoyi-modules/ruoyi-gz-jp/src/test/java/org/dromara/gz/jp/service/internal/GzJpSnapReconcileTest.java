package org.dromara.gz.jp.service.internal;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
@Tag("dev") class GzJpSnapReconcileTest {
 @Test void soldNotFoundWaitsAllThreeRoundsWithoutRefund() throws Exception {var f=new Snap305Fixture();var r=f.json.readTree("{\"found\":false,\"item_status\":\"sold_out\"}");for(int i=1;i<=3;i++){f.tx.result(f.task,r,true,null,null);assertEquals(i,f.task.getReconcileCount());assertEquals(i==3?"manual_pending":"unknown",f.task.getPurchaseStatus());}assertEquals("reconcile_sold",f.task.getManualReason());verifyNoInteractions(f.refunds);verify(f.snaps,never()).releaseSnapLock(anyLong());}
 @Test void secondRoundFoundSucceeds() throws Exception {var f=new Snap305Fixture();f.tx.result(f.task,f.json.readTree("{\"found\":false}"),true,null,null);f.tx.result(f.task,f.json.readTree("{\"found\":true,\"mercari_txn_id\":\"T305\",\"actual_price_jpy\":800}"),true,null,null);assertEquals("success",f.task.getPurchaseStatus());assertEquals("await_ship",f.task.getTradeStatus());assertEquals(800L,f.task.getActualPriceJpy());verifyNoInteractions(f.refunds);}
 @Test void errorsExhaustOnlyToManual() {var f=new Snap305Fixture();for(int i=0;i<3;i++)f.tx.result(f.task,null,true,null,"network failed");assertEquals("manual_pending",f.task.getPurchaseStatus());assertEquals("reconcile_exhausted",f.task.getManualReason());verifyNoInteractions(f.refunds);}
 @Test void intervalsAreTwoAndTenMinutes() {var f=new Snap305Fixture();f.tx.result(f.task,null,true,null,null);assertTrue(f.task.getNextActionAt().isAfter(java.time.LocalDateTime.now().plusSeconds(119)));f.tx.result(f.task,null,true,null,null);assertTrue(f.task.getNextActionAt().isAfter(java.time.LocalDateTime.now().plusSeconds(599)));}
 @Test void duplicateTerminalResultDoesNothing() throws Exception {var f=new Snap305Fixture();f.task.setPurchaseStatus("success");f.tx.result(f.task,f.json.readTree("{\"found\":false}"),true,null,null);verify(f.tasks,never()).saveOutcome(any());verifyNoInteractions(f.refunds);}
 @Test void malformedFoundRemainsUnknown() throws Exception {var f=new Snap305Fixture();f.tx.result(f.task,f.json.readTree("{\"found\":true}"),true,null,null);assertEquals("unknown",f.task.getPurchaseStatus());verifyNoInteractions(f.refunds);}
}
