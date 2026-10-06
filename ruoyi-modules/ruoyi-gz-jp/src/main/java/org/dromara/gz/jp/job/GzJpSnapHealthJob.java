package org.dromara.gz.jp.job;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.redis.utils.RedisUtils;
import org.dromara.common.tenant.helper.TenantHelper;
import org.dromara.gz.jp.domain.entity.GzJpSnapAccount;
import org.dromara.gz.jp.mapper.GzJpSnapAccountMapper;
import org.dromara.gz.jp.snap.gateway.*;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class GzJpSnapHealthJob {
    public static final String PROBE_REDIS_KEY = "1001:gz:jp:snap:probe";
    private final GzJpGatewayClient gateway;
    private final GzJpGatewayProperties properties;
    private final GzJpSnapAccountMapper accounts;
    private final GzJpSnapRiskControl riskControl;

    public void heartbeat() {
        Map<String, String> pauses = new HashMap<>();
        TenantHelper.dynamic("1001", () -> {
            JsonNode response;
            try { response = gateway.health(); }
            catch (GzJpGatewayException ex) {
                log.error("[jp-snap] gateway heartbeat failed: {}", ex.getKind());
                for (GzJpSnapAccount account : accounts.selectHealthAccounts()) {
                    accounts.recordHealth(account.getAccountCode(), "unreachable", false, LocalDateTime.now());
                }
                return;
            }
            Map<String, String> sessions = new HashMap<>();
            for (JsonNode account : response.path("accounts")) sessions.put(account.path("account_code").asText(), account.path("session").asText());
            for (GzJpSnapAccount account : accounts.selectHealthAccounts()) {
                String session = sessions.get(account.getAccountCode());
                String health = !response.path("egress_ok").asBoolean() ? "unreachable"
                    : "ok".equals(session) ? "healthy" : "logged_out".equals(session) ? "logged_out"
                    : "challenge".equals(session) ? "challenge" : "unreachable";
                accounts.recordHealth(account.getAccountCode(), health, !"unreachable".equals(health), LocalDateTime.now());
                if (Boolean.TRUE.equals(account.getAdminEnabled()) && ("logged_out".equals(health) || "challenge".equals(health))) {
                    pauses.put(account.getAccountCode(), "health:" + health);
                }
                // Successful heartbeats never clear pause_reason or restore the auto switch.
            }
        });
        // TenantHelper.dynamic is not reentrant: invoke the shared control after leaving
        // this tenant scope, otherwise its finally block clears our remaining heartbeat writes.
        pauses.forEach(riskControl::pause);
    }

    public void probe() {
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        result.put("checkedAt", OffsetDateTime.now().toString());
        try {
            gateway.search(properties.getProbeKeyword(), "created_desc", 1, "");
            gateway.item(properties.getProbeItemId());
            result.put("ok", true);
        } catch (GzJpGatewayException ex) {
            result.put("ok", false);
            result.put("reason", "probe:" + ex.getKind().name().toLowerCase(java.util.Locale.ROOT));
            riskControl.pause(null, result.path("reason").asText());
            log.error("[jp-snap] active probe failed: {} (HTTP {})", ex.getKind(), ex.getHttpStatus());
        } finally {
            saveProbe(result);
        }
    }
    protected void saveProbe(ObjectNode result) {
        RedisUtils.setCacheObject(PROBE_REDIS_KEY, result.toString(), Duration.ofHours(24));
    }
}
