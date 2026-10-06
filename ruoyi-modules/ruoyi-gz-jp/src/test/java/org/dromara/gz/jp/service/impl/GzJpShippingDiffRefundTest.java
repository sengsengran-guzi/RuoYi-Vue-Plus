package org.dromara.gz.jp.service.impl;
import org.dromara.gz.jp.domain.entity.GzJpRefund;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
@Tag("dev")
class GzJpShippingDiffRefundTest {
 private GzJpRefundFixture fixture(){var f=new GzJpRefundFixture().paidOrder(1,1,1000).item(2,1,1000,"purchasing");var row=f.items.get(2L);row.setSource("snap");row.setProductSnapshotJson("{\"snap\":{\"shippingPayer\":\"buyer\",\"ratePer100Jpy\":4.123,\"estShippingCent\":500}}");return f;}
 @Test void diffUsesSnapshotCeilingAndNeverRollsUp(){var f=fixture();var p=f.txService.createShippingDiff(2L,100L);var r=p.getPending().get(0).getRefund();assertEquals(87,r.getRefundAmountCent());assertTrue(f.txService.applyRefundSuccess(r,"wx"));assertEquals("paid",f.orders.get(1L).getBusinessStatus());assertNull(f.items.get(2L).getRefundStatus());assertEquals("purchasing",f.items.get(2L).getFulfillStatus());assertFalse(f.txService.applyRefundSuccess(r,"wx"));assertEquals(1,f.txService.createShippingDiff(2L,100L).getRefundsSkipped());}
 @Test void nonPositiveDiffDoesNothing(){var f=fixture();assertTrue(f.txService.createShippingDiff(2L,200L).getPending().isEmpty());assertTrue(f.refunds.isEmpty());}
 @Test void diffFailureAcceptanceFailureAndRetryNeverTouchItem(){var f=fixture();var r=f.txService.createShippingDiff(2L,100L).getPending().get(0).getRefund();assertTrue(f.txService.markAcceptFailedIfRefunding(r.getId(),2L,"reject"));assertNull(f.items.get(2L).getRefundStatus());assertTrue(f.txService.markRetrying(r.getId(),2L));assertNull(f.items.get(2L).getRefundStatus());assertTrue(f.txService.applyRefundFailure(r,"wx","closed"));assertNull(f.items.get(2L).getRefundStatus());verify(f.itemMapper,never()).writeRefundResult(anyLong(),anyString(),any());}
 @Test void failureRefundDeductsSuccessfulDiff(){var f=fixture();var r=f.txService.createShippingDiff(2L,100L).getPending().get(0).getRefund();f.txService.applyRefundSuccess(r,"wx");var p=f.txService.markFailedAndCreateRefunds(java.util.List.of(2L),null,1L,"tester");assertEquals(913,p.getPending().get(0).getRefund().getRefundAmountCent());assertEquals(1000,f.refunds.values().stream().mapToLong(GzJpRefund::getRefundAmountCent).sum());}
 @Test void pendingDiffBlocksFullRefund(){var f=fixture();f.txService.createShippingDiff(2L,100L);assertThrows(org.dromara.common.core.exception.ServiceException.class,()->f.txService.markFailedAndCreateRefunds(java.util.List.of(2L),null,1L,"tester"));}
 @Test void failedDiffCannotBeRetriedAfterFullRefundReserved(){var f=fixture();var r=f.txService.createShippingDiff(2L,100L).getPending().get(0).getRefund();f.txService.applyRefundFailure(r,null,"closed");f.txService.markFailedAndCreateRefunds(java.util.List.of(2L),null,1L,"tester");assertThrows(org.dromara.common.core.exception.ServiceException.class,()->f.txService.markRetrying(r.getId(),2L));}
}
