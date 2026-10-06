package org.dromara.gz.jp.service.internal;
import org.junit.jupiter.api.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
@Tag("dev") class GzJpSnapExecutorTest {
 @Test void coolingWaitsInsteadOfManual() {var f=new Snap305Fixture();f.task.setPurchaseStatus("queued");var a=f.account();a.setLastPurchaseAt(LocalDateTime.now());when(f.accounts.lockAccounts()).thenReturn(List.of(a));assertNull(f.tx.claim(1L,LocalDateTime.now()));assertEquals("queued",f.task.getPurchaseStatus());assertNotNull(f.task.getNextActionAt());verify(f.tasks,never()).claim(anyLong(),anyInt(),anyString(),any());}
 @Test void noAccountBecomesManual() {var f=new Snap305Fixture();f.task.setPurchaseStatus("queued");when(f.accounts.lockAccounts()).thenReturn(List.of());f.tx.claim(1L,LocalDateTime.now());assertEquals("manual_pending",f.task.getPurchaseStatus());assertEquals("no_account",f.task.getManualReason());}
 @Test void dayLimitBecomesManual() {var f=new Snap305Fixture();f.task.setPurchaseStatus("queued");var a=f.account();a.setTodayCount(10);when(f.accounts.lockAccounts()).thenReturn(List.of(a));f.tx.claim(1L,LocalDateTime.now());assertEquals("no_account",f.task.getManualReason());}
 @Test void tokyoNewDayResetsCountAndClaimsOnce(){var f=new Snap305Fixture();f.task.setPurchaseStatus("queued");var a=f.account();a.setTodayCount(10);a.setTodayDate(LocalDate.now(ZoneId.of("Asia/Tokyo")).minusDays(1));when(f.accounts.lockAccounts()).thenReturn(List.of(a));when(f.tasks.claim(anyLong(),anyInt(),anyString(),any())).thenReturn(1);when(f.accounts.consume(anyLong(),any(),any())).thenReturn(1);assertNotNull(f.tx.claim(1L,LocalDateTime.now()));assertEquals("executing",f.task.getPurchaseStatus());verify(f.accounts).consume(eq(10L),eq(LocalDate.now(ZoneId.of("Asia/Tokyo"))),any());}
 @Test void shippingChangedAndUnpayableNeverRefund() throws Exception {for(String reason:List.of("shipping_changed","account_unpayable")){var f=new Snap305Fixture();f.task.setPurchaseStatus("executing");f.tx.result(f.task,f.json.readTree("{\"result\":\"failed\",\"reason\":\""+reason+"\"}"),false,null,null);assertEquals("manual_pending",f.task.getPurchaseStatus());assertEquals(reason,f.task.getManualReason());verifyNoInteractions(f.refunds);verify(f.runtime,never()).writeAutoConfig(anyString());}}
 @Test void timeoutNeverRefundsOrRequeues(){var f=new Snap305Fixture();f.task.setPurchaseStatus("executing");f.tx.result(f.task,null,false,null,"timeout");assertEquals("unknown",f.task.getPurchaseStatus());assertTrue(f.task.getNextActionAt().isAfter(LocalDateTime.now().plusSeconds(29)));verifyNoInteractions(f.refunds);}
 @Test void disabledSwitchDoesNotClaim(){var f=new Snap305Fixture();when(f.runtime.lockAutoConfig()).thenReturn("{\"enabled\":false}");assertNull(f.tx.claim(1L,LocalDateTime.now()));verifyNoInteractions(f.accounts);}
 @Test void alreadyExecutingAccountWaits(){var f=new Snap305Fixture();f.task.setPurchaseStatus("queued");when(f.accounts.lockAccounts()).thenReturn(List.of(f.account()));when(f.tasks.executing("acc01")).thenReturn(1);assertNull(f.tx.claim(1L,LocalDateTime.now()));assertEquals("queued",f.task.getPurchaseStatus());}

 @Test void failedTransitionCreatesRefundBeforeReleasingMatchingLock() throws Exception {
  var f=new Snap305Fixture();f.task.setPurchaseStatus("executing");var plan=new org.dromara.gz.jp.service.internal.dto.GzJpRefundPlan();
  when(f.refunds.markFailedAndCreateRefunds(anyList(),anyString(),isNull(),anyString())).thenReturn(plan);
  assertSame(plan,f.tx.result(f.task,f.json.readTree("{\"result\":\"failed\",\"reason\":\"sold_out\"}"),false,null,null));
  var order=inOrder(f.tasks,f.refunds,f.snaps);order.verify(f.snaps).lockSnapById(3L);order.verify(f.tasks).lock(1L);order.verify(f.tasks).saveOutcome(any());order.verify(f.refunds).markFailedAndCreateRefunds(eq(List.of(2L)),anyString(),isNull(),eq("snap_scheduler"));order.verify(f.snaps).releaseSnapLock(2L);
 }
 @Test void rejectedRefundRollsBackInsteadOfReleasingLock() throws Exception {
  var f=new Snap305Fixture();f.task.setPurchaseStatus("executing");var plan=new org.dromara.gz.jp.service.internal.dto.GzJpRefundPlan();plan.rejectRefund(2L,"missing payment",500);
  when(f.refunds.markFailedAndCreateRefunds(anyList(),anyString(),isNull(),anyString())).thenReturn(plan);
  assertThrows(IllegalStateException.class,()->f.tx.result(f.task,f.json.readTree("{\"result\":\"failed\",\"reason\":\"sold_out\"}"),false,null,null));verify(f.snaps,never()).releaseSnapLock(anyLong());
 }
}
