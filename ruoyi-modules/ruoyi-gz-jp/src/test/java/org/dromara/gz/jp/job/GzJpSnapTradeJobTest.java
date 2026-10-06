package org.dromara.gz.jp.job;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.dromara.common.tenant.helper.TenantHelper;
import org.dromara.gz.jp.domain.entity.GzJpSnapPurchase;
import org.dromara.gz.jp.mapper.GzJpSnapPurchaseMapper;
import org.dromara.gz.jp.service.internal.*;
import org.dromara.gz.jp.snap.gateway.GzJpGatewayClient;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;
import java.util.List;
import static org.mockito.Mockito.*;

@Tag("dev") class GzJpSnapTradeJobTest {
 private final GzJpSnapPurchaseMapper tasks=mock(GzJpSnapPurchaseMapper.class);
 private final GzJpSnapTradeService service=mock(GzJpSnapTradeService.class);
 private final GzJpSnapTradeTx tx=mock(GzJpSnapTradeTx.class);
 private final GzJpGatewayClient gateway=mock(GzJpGatewayClient.class);
 private GzJpSnapTradeJob job;
 private MockedStatic<TenantHelper> tenant;
 @BeforeEach void setup() throws Exception {
  tenant=mockStatic(TenantHelper.class);tenant.when(()->TenantHelper.dynamic(eq("1001"),any(Runnable.class))).thenAnswer(c->{((Runnable)c.getArgument(1)).run();return null;});
  job=new GzJpSnapTradeJob(tasks,service,tx,gateway);var field=GzJpSnapTradeJob.class.getDeclaredField("schedulerEnabled");field.setAccessible(true);field.set(job,true);
  when(tx.setting("patrolMinutes",20)).thenReturn(20);
  when(tasks.patrolTrades()).thenReturn(List.of(GzJpSnapPurchase.builder().id(1L).accountCode("acc01").mercariTxnId("txn1").externalId("m1").build(),GzJpSnapPurchase.builder().id(2L).accountCode("acc01").mercariTxnId("txn2").externalId("m2").build()));
 }
 @AfterEach void cleanup(){tenant.close();}
 @Test void matchesAccountTransactionAndItemUsesSameExitAndRespectsInterval() throws Exception {
  when(gateway.trades("acc01")).thenReturn(new ObjectMapper().readTree("{\"trades\":[{\"mercari_txn_id\":\"txn1\",\"external_id\":\"m1\",\"trade_status\":\"shipped\"},{\"mercari_txn_id\":\"txn2\",\"external_id\":\"m2\",\"trade_status\":\"cancelled\"},{\"mercari_txn_id\":\"txn1\",\"external_id\":\"wrong\",\"trade_status\":\"cancelled\"}]}"));
  job.tick();job.tick();verify(gateway,times(1)).trades("acc01");verify(service).transition(1L,"shipped",null,null);verify(service).transition(2L,"cancelled",null,null);verifyNoMoreInteractions(service);
 }
 @Test void failedReadAndCompletedStatusNeverAdvance() throws Exception {
  when(gateway.trades("acc01")).thenThrow(new IllegalStateException("offline"));job.tick();verifyNoInteractions(service);
 }
}
