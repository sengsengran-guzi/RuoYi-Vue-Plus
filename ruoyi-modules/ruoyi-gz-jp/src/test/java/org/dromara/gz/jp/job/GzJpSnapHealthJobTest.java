package org.dromara.gz.jp.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.dromara.common.tenant.helper.TenantHelper;
import org.dromara.gz.jp.domain.entity.GzJpSnapAccount;
import org.dromara.gz.jp.mapper.GzJpSnapAccountMapper;
import org.dromara.gz.jp.mapper.GzJpSnapRuntimeConfigMapper;
import org.dromara.gz.jp.snap.gateway.*;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@Tag("dev")
class GzJpSnapHealthJobTest {
    ObjectMapper json = new ObjectMapper();
    GzJpGatewayClient gateway = mock(GzJpGatewayClient.class);
    GzJpGatewayProperties properties = new GzJpGatewayProperties();
    GzJpSnapAccountMapper accounts = mock(GzJpSnapAccountMapper.class);
    GzJpSnapRiskControl risk = mock(GzJpSnapRiskControl.class);
    MockedStatic<TenantHelper> tenant;
    ObjectNode probeResult;
    GzJpSnapHealthJob job;
    @BeforeEach void setup() {
        tenant = mockStatic(TenantHelper.class);
        tenant.when(() -> TenantHelper.dynamic(eq("1001"), any(Runnable.class)))
            .thenAnswer(call -> { ((Runnable) call.getArgument(1)).run(); return null; });
        job = new GzJpSnapHealthJob(gateway, properties, accounts, risk) {
            @Override protected void saveProbe(ObjectNode result) { probeResult = result; }
        };
        when(accounts.selectHealthAccounts()).thenReturn(List.of(GzJpSnapAccount.builder().accountCode("acc01").adminEnabled(true).build()));
    }
    @AfterEach void cleanup() { tenant.close(); }

    @Test void lostLoginAndChallengePauseWithReason() throws Exception {
        for (String session : List.of("logged_out", "challenge")) {
            when(gateway.health()).thenReturn(json.readTree("{\"egress_ok\":true,\"accounts\":[{\"account_code\":\"acc01\",\"session\":\"" + session + "\"}]}"));
            job.heartbeat();
            verify(accounts).recordHealth(eq("acc01"), eq(session), eq(true), any());
            verify(risk).pause("acc01", "health:" + session);
        }
        verify(accounts, never()).updateById(any(GzJpSnapAccount.class));
    }
    @Test void healthyDoesNotResumeManualPauseOrAutoSwitch() throws Exception {
        when(gateway.health()).thenReturn(json.readTree("{\"egress_ok\":true,\"accounts\":[{\"account_code\":\"acc01\",\"session\":\"ok\"}]}"));
        job.heartbeat();
        verify(accounts).recordHealth(eq("acc01"), eq("healthy"), eq(true), any());
        verifyNoInteractions(risk);
        verify(accounts, never()).pauseAccount(anyString(), anyString());
    }
    @Test void unreachableDoesNotForgeLastSuccessfulHeartbeat() {
        when(gateway.health()).thenThrow(new GzJpGatewayException(GzJpGatewayException.Kind.TIMEOUT, 0, "timeout", null));
        job.heartbeat();
        verify(accounts).recordHealth(eq("acc01"), eq("unreachable"), eq(false), any());
    }
    @Test void missingAccountOrEgressFailureIsNotHealthy() throws Exception {
        when(gateway.health()).thenReturn(json.readTree("{\"egress_ok\":true,\"accounts\":[]}"));
        job.heartbeat();
        verify(accounts).recordHealth(eq("acc01"), eq("unreachable"), eq(false), any());
    }
    @Test void disabledAdminAccountIsNeverAutoResumed() throws Exception {
        when(accounts.selectHealthAccounts()).thenReturn(List.of(GzJpSnapAccount.builder().accountCode("acc01").adminEnabled(false).build()));
        when(gateway.health()).thenReturn(json.readTree("{\"egress_ok\":true,\"accounts\":[{\"account_code\":\"acc01\",\"session\":\"challenge\"}]}"));
        job.heartbeat();
        verifyNoInteractions(risk);
    }
    @Test void probeFailureClosesSwitchAndPublishesFailure() {
        when(gateway.item(anyString())).thenThrow(new GzJpGatewayException(GzJpGatewayException.Kind.HTTP_STATUS, 404, "not found", null));
        job.probe();
        verify(gateway).search(properties.getProbeKeyword(), "created_desc", 1, "");
        verify(risk).pause(null, "probe:http_status");
        assertFalse(probeResult.path("ok").asBoolean());
        assertTrue(probeResult.hasNonNull("checkedAt"));
    }
    @Test void successfulProbeNeverRestoresSwitch() {
        job.probe();
        assertTrue(probeResult.path("ok").asBoolean());
        verifyNoInteractions(risk);
    }
    @Test void sharedRiskPreservesJsonAndEvictsAfterCommit() throws Exception {
        GzJpSnapRuntimeConfigMapper config = mock(GzJpSnapRuntimeConfigMapper.class);
        when(config.lockAutoConfig()).thenReturn("{\"enabled\":true,\"customLimit\":7}");
        when(config.writeAutoConfig(anyString())).thenReturn(1);
        when(accounts.pauseAccount("acc01", "challenge")).thenReturn(1);
        ConcurrentMapCacheManager caches = new ConcurrentMapCacheManager("sys_config");
        caches.getCache("sys_config").put("gz.jp.snap.auto", "stale enabled=true");
        GzJpSnapRiskControl control = new GzJpSnapRiskControl(config, accounts, json, caches);
        TransactionSynchronizationManager.initSynchronization();
        try {
            control.pause("acc01", "challenge");
            var captor = org.mockito.ArgumentCaptor.forClass(String.class);
            verify(config).writeAutoConfig(captor.capture());
            var value = json.readTree(captor.getValue());
            assertFalse(value.path("enabled").asBoolean());
            assertEquals(7, value.path("customLimit").asInt());
            assertEquals("challenge", value.path("pausedReason").asText());
            assertTrue(value.hasNonNull("pausedAt"));
            assertNotNull(caches.getCache("sys_config").get("gz.jp.snap.auto"));
            TransactionSynchronizationManager.getSynchronizations().forEach(sync -> sync.afterCommit());
            assertNull(caches.getCache("sys_config").get("gz.jp.snap.auto"));
        } finally { TransactionSynchronizationManager.clearSynchronization(); }
    }
}
