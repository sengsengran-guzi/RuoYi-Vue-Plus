package org.dromara.gz.jp.service.internal;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.dromara.gz.jp.domain.entity.GzJpSnapAccount;
import org.dromara.gz.jp.mapper.*;
import org.dromara.gz.jp.snap.gateway.GzJpSnapRiskControl;
import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.*;
import org.springframework.cache.CacheManager;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
@Tag("dev")
class GzJpSnapAutoSwitchTest {
 GzJpSnapAccountMapper accounts; GzJpSnapRuntimeConfigMapper configs; GzJpSnapRiskControl risk;
 GzJpSnapAccountAdminService service;
 @BeforeEach void setup(){ accounts=mock(GzJpSnapAccountMapper.class); configs=mock(GzJpSnapRuntimeConfigMapper.class);risk=mock(GzJpSnapRiskControl.class);
 service=new GzJpSnapAccountAdminService(accounts,configs,risk,new ObjectMapper(),mock(CacheManager.class));
 when(configs.lockAutoConfig()).thenReturn("{\"enabled\":false}"); }
 GzJpSnapAccount account(boolean enabled,String health,String pause){return GzJpSnapAccount.builder().adminEnabled(enabled).healthStatus(health).pauseReason(pause).build();}
 @Test void confirmationRequired(){assertThrows(ServiceException.class,()->service.resume(false));verifyNoInteractions(configs,accounts);}
 @Test void emptyPoolRejected(){when(accounts.lockAccounts()).thenReturn(List.of());assertThrows(ServiceException.class,()->service.resume(true));verify(configs,never()).writeAutoConfig(anyString());}
 @Test void disabledHealthyRejected(){when(accounts.lockAccounts()).thenReturn(List.of(account(false,"healthy",null)));assertThrows(ServiceException.class,()->service.resume(true));}
 @Test void pausedHealthyRejected(){when(accounts.lockAccounts()).thenReturn(List.of(account(true,"healthy","balance")));assertThrows(ServiceException.class,()->service.resume(true));}
 @Test void challengeEvenDisabledBlocks(){when(accounts.lockAccounts()).thenReturn(List.of(account(true,"healthy",null),account(false,"challenge",null)));assertThrows(ServiceException.class,()->service.resume(true));}
 @Test void unhealthyRejected(){when(accounts.lockAccounts()).thenReturn(List.of(account(true,"logged_out",null)));assertThrows(ServiceException.class,()->service.resume(true));}
 @Test void healthyResumeWritesOnlyConfig(){when(accounts.lockAccounts()).thenReturn(List.of(account(true,"healthy",null)));when(configs.writeAutoConfig(anyString())).thenReturn(1);
 // register afterCommit without invoking static TenantHelper outside the Spring application.
 org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
 try{service.resume(true);verify(configs).writeAutoConfig("{\"enabled\":true,\"pausedReason\":null,\"pausedAt\":null}");verify(accounts,never()).recordHealth(anyString(),anyString(),anyBoolean(),any());verifyNoInteractions(risk);}
 finally{org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization();}}
 @Test void pauseUsesSharedCircuitBreaker(){service.pause("manual");verify(risk).pause(null,"manual");}
 @Test void yesterdayCounterDisplaysZeroWithoutWrites(){var a=account(true,"healthy",null);a.setTodayDate(LocalDate.now(ZoneId.of("Asia/Tokyo")).minusDays(1));a.setTodayCount(8);assertEquals(0,GzJpSnapAccountAdminService.row(a).todayCount());assertEquals(8,a.getTodayCount());}
 @Test void currentTokyoCounterVisible(){var a=account(true,"healthy",null);a.setTodayDate(LocalDate.now(ZoneId.of("Asia/Tokyo")));a.setTodayCount(8);assertEquals(8,GzJpSnapAccountAdminService.row(a).todayCount());}
 @Test void editsNeverChangeHealthOrCode(){when(accounts.editAdmin(1L,"N",5,60)).thenReturn(1);service.edit(1L,"N",5,60);verify(accounts).editAdmin(1L,"N",5,60);verifyNoMoreInteractions(accounts);}
}
