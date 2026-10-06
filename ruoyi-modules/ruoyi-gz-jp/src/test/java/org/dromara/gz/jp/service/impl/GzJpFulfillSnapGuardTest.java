package org.dromara.gz.jp.service.impl;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.gz.jp.domain.bo.*;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
@Tag("dev") class GzJpFulfillSnapGuardTest {
 private GzJpFulfillFixture fixture(String snapStatus){var f=new GzJpFulfillFixture().paidOrder(1,1).item(2,1,"await_seller_ship").item(3,1,"await_seller_ship");f.row(2).setSource("snap");f.row(3).setSource("batch");doCallRealMethod().when(f.itemMapper).assertSnapRated(anyList());when(f.itemMapper.snapTradeStatusForUpdate(2L)).thenReturn(snapStatus);return f;}
 @Test void advanceRejectsEntireMixedBatchAndIdentifiesRow(){var f=fixture("arrived");var bo=new GzJpFulfillAdvanceBo();bo.setItemIds(List.of(2L,3L));bo.setTargetStatus("jp_shipped");var e=assertThrows(ServiceException.class,()->f.service.advance(bo,1L));assertTrue(e.getMessage().contains("2"));assertEquals("await_seller_ship",f.statusOf(3));verify(f.itemMapper,never()).advanceGuarded(anyList(),anyString(),anyString(),any());}
 @Test void shipRejectsEntireMixedBatch(){var f=fixture("shipped");var bo=new GzJpFulfillShipBo();bo.setItemIds(List.of(2L,3L));bo.setCarrierCode("sf");bo.setTrackingNo("SNAP307");assertThrows(ServiceException.class,()->f.service.ship(bo,1L));verify(f.itemMapper,never()).shipGuarded(anyList(),anyString(),anyString(),anyString(),any(),any());verifyNoInteractions(f.shippingService);}
 @Test void ratedAndBatchFollowExistingFlow(){var f=fixture("rated");var bo=new GzJpFulfillAdvanceBo();bo.setItemIds(List.of(2L,3L));bo.setTargetStatus("jp_shipped");assertEquals(2,f.service.advance(bo,1L).getAdvanced());}
 @Test void missingTaskAlsoBlocks(){var f=fixture(null);assertThrows(ServiceException.class,()->f.itemMapper.assertSnapRated(List.of(f.row(2),f.row(3))));}
 @Test void markFailedAdminGuardCannotBeBypassedAndSystemExitRemainsAvailable(){var f=new GzJpRefundFixture().paidOrder(1,1,1000).item(2,1,1000,"await_seller_ship");f.items.get(2L).setSource("snap");doCallRealMethod().when(f.itemMapper).assertSnapRated(anyList());when(f.itemMapper.snapTradeStatusForUpdate(2L)).thenReturn("arrived");var bo=new GzJpMarkFailedBo();bo.setItemIds(List.of(2L));bo.setReason("guard");var e=assertThrows(ServiceException.class,()->f.service.markPurchaseFailedAndRefund(bo,1L,"admin"));assertTrue(e.getMessage().contains("2"));assertTrue(f.refunds.isEmpty());assertEquals("await_seller_ship",f.items.get(2L).getFulfillStatus());var p=f.txService.markFailedAndCreateRefunds(List.of(2L),"seller cancel",null,"snap_trade");assertEquals(1000,p.getPending().get(0).getRefund().getRefundAmountCent());}
}
