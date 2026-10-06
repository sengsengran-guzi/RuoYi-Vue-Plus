package org.dromara.gz.jp.service.internal;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.dromara.common.core.service.ConfigService;
import org.dromara.gz.jp.domain.entity.*;
import org.dromara.gz.jp.mapper.*;
import java.time.*;
import java.util.*;
import static org.mockito.Mockito.*;
class Snap305Fixture {
 final GzJpSnapPurchaseMapper tasks=mock(GzJpSnapPurchaseMapper.class);
 final GzJpSnapAccountMapper accounts=mock(GzJpSnapAccountMapper.class);
 final GzJpSnapItemMapper snaps=mock(GzJpSnapItemMapper.class);
 final GzJpOrderMapper orders=mock(GzJpOrderMapper.class);
 final GzJpOrderItemMapper items=mock(GzJpOrderItemMapper.class);
 final GzJpRefundTxService refunds=mock(GzJpRefundTxService.class);
 final ConfigService config=mock(ConfigService.class);
 final GzJpSnapRuntimeConfigMapper runtime=mock(GzJpSnapRuntimeConfigMapper.class);
 final ObjectMapper json=new ObjectMapper();
 final GzJpSnapExecutorTx tx=new GzJpSnapExecutorTx(tasks,accounts,snaps,orders,items,refunds,config,runtime,json);
 final GzJpSnapPurchase task=GzJpSnapPurchase.builder().id(1L).orderItemId(2L).snapItemId(3L).orderNo("JPO-305").externalId("m305").purchaseStatus("unknown").reconcileCount(0).version(0).accountCode("acc01").build();
 Snap305Fixture(){
  var item=new GzJpOrderItem();item.setId(2L);item.setOrderId(4L);item.setFulfillStatus("purchasing");
  when(items.selectById(2L)).thenReturn(item);when(items.lockById(2L)).thenReturn(item);when(tasks.lock(1L)).thenReturn(task);when(tasks.saveOutcome(any())).thenReturn(1);when(items.advanceGuarded(anyList(),anyString(),anyString(),isNull())).thenReturn(1);
  when(config.getConfigValue("gz.jp.snap.auto")).thenReturn("{\"enabled\":true}");when(runtime.lockAutoConfig()).thenReturn("{\"enabled\":true}");
 }
 GzJpSnapAccount account(){return GzJpSnapAccount.builder().id(10L).accountCode("acc01").adminEnabled(true).healthStatus("healthy").dailyLimit(10).todayCount(0).todayDate(LocalDate.now(ZoneId.of("Asia/Tokyo"))).cooldownSec(60).build();}
}
