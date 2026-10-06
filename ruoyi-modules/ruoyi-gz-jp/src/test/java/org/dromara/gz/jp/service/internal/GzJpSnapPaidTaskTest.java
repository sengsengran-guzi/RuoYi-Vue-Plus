package org.dromara.gz.jp.service.internal;
import org.junit.jupiter.api.*;
import org.dromara.gz.jp.domain.dto.JpOrderSnapshot;
import org.dromara.gz.jp.domain.entity.*;
import org.dromara.gz.jp.mapper.*;
import org.dromara.gz.jp.service.impl.GzJpOrderServiceImpl;
import org.dromara.common.core.service.ConfigService;
import org.dromara.gz.common.pay.domain.entity.GzPayTransaction;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.mockito.ArgumentCaptor;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
@Tag("dev")
class GzJpSnapPaidTaskTest {
    GzJpSnapPurchaseMapper tasks=mock(GzJpSnapPurchaseMapper.class);
    ConfigService config=mock(ConfigService.class);
    ObjectMapper json=new ObjectMapper();
    GzJpSnapPaidTaskService service=new GzJpSnapPaidTaskService(tasks,config,json);
    GzJpOrder order=new GzJpOrder();
    GzJpOrderItem row=new GzJpOrderItem();
    @BeforeEach void before() {order.setId(1L);order.setOrderNo("JPO-test");row.setId(2L);row.setOrderId(1L);row.setSnapItemId(3L);row.setSource("snap");}
    void snapshot(String gate,String reason)throws Exception {row.setProductSnapshotJson(json.writeValueAsString(JpOrderSnapshot.Product.builder().snap(JpOrderSnapshot.Snap.builder().externalId("m1").priceJpy(800L).gateResult(gate).gateReason(reason).build()).build()));}
    GzJpSnapPurchase saved() {var captor=ArgumentCaptor.forClass(GzJpSnapPurchase.class);verify(tasks).insertIfAbsent(captor.capture());return captor.getValue();}
    @Test void autoQueuesLockedPrice()throws Exception {snapshot("auto",null);when(config.getConfigValue("gz.jp.snap.auto")).thenReturn("{\"enabled\":true}");service.enqueue(order,row);var task=saved();assertEquals("queued",task.getPurchaseStatus());assertEquals(800L,task.getLockedPriceJpy());assertNotNull(task.getNextActionAt());assertEquals(0,task.getVersion());assertEquals("JPO-test",task.getOrderNo());}
    @Test void manualGateBypassesEnabledAuto()throws Exception {snapshot("manual","buyer_shipping");when(config.getConfigValue(anyString())).thenReturn("{\"enabled\":true}");service.enqueue(order,row);var task=saved();assertEquals("manual_pending",task.getPurchaseStatus());assertEquals("gate:buyer_shipping",task.getManualReason());assertNull(task.getNextActionAt());
        clearInvocations(tasks);String full="comment_required:"+"長".repeat(80);snapshot("manual",full);service.enqueue(order,row);
        var bounded=saved().getManualReason();assertEquals(64,bounded.codePointCount(0,bounded.length()));
        assertEquals(full,json.readValue(row.getProductSnapshotJson(),JpOrderSnapshot.Product.class).getSnap().getGateReason());}
    @Test void pauseSendsAutoToManual()throws Exception {snapshot("auto",null);when(config.getConfigValue(anyString())).thenReturn("{\"enabled\":false}");service.enqueue(order,row);assertEquals("auto_paused",saved().getManualReason());}
    @Test void missingConfigFailsClosed()throws Exception {snapshot("auto",null);when(config.getConfigValue(anyString())).thenReturn("");service.enqueue(order,row);assertEquals("manual",saved().getMode());}
    @Test void invalidSnapshotRollsBack() {row.setProductSnapshotJson("{}");assertThrows(org.dromara.common.core.exception.ServiceException.class,()->service.enqueue(order,row));verifyNoInteractions(tasks);}
    @Test void repeatedPaymentDoesNotEnqueueTwice()throws Exception {
        snapshot("auto",null);var orders=mock(GzJpOrderMapper.class);var rows=mock(GzJpOrderItemMapper.class);
        when(orders.selectByOrderNoForUpdate("JPO-test")).thenReturn(order);when(orders.markPaid(any(),any(),any())).thenReturn(1,0);
        when(rows.selectList(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(java.util.List.of(row));
        var ordersService=new GzJpOrderServiceImpl(orders,rows,null,null,null,null,null,null,null,null,null,json,service,null, org.mockito.Mockito.mock(org.dromara.gz.jp.service.internal.GzJpSnapOrderDisplayService.class));
        var txn=new GzPayTransaction();txn.setId(9L);txn.setBusinessOrderNo("JPO-test");txn.setPaidTime(java.time.LocalDateTime.now());
        ordersService.onPaid(txn);ordersService.onPaid(txn);verify(tasks,times(1)).insertIfAbsent(any());verify(rows,times(1)).activatePurchasing(1L);
    }
}
