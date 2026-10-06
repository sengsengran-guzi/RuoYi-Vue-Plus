package org.dromara.gz.jp.snap.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.constant.CacheNames;
import org.dromara.common.tenant.helper.TenantHelper;
import org.dromara.gz.jp.mapper.GzJpSnapAccountMapper;
import org.dromara.gz.jp.mapper.GzJpSnapRuntimeConfigMapper;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.OffsetDateTime;
import java.util.Objects;

/** Shared fail-closed entry point for health/probe and GZ-SNAP-305. Never resumes an account. */
@Service
@RequiredArgsConstructor
public class GzJpSnapRiskControl {
    public static final String AUTO_CONFIG_KEY = "gz.jp.snap.auto";
    private final GzJpSnapRuntimeConfigMapper configMapper;
    private final GzJpSnapAccountMapper accountMapper;
    private final ObjectMapper json;
    private final org.dromara.gz.jp.mapper.GzJpSnapPurchaseMapper purchases;
    private final CacheManager cacheManager;

    @Transactional(rollbackFor = Exception.class)
    public void pause(String accountCode, String reason) {
        if (reason == null || reason.isBlank() || reason.length() > 255) throw new IllegalArgumentException("Pause reason must contain 1..255 characters");
        TenantHelper.dynamic("1001", () -> {
            try {
                String raw = configMapper.lockAutoConfig();
                if (raw == null) throw new IllegalStateException("Missing " + AUTO_CONFIG_KEY + " for tenant 1001");
                ObjectNode value = (ObjectNode) json.readTree(raw);
                value.put("enabled", false);
                value.put("pausedReason", reason);
                value.put("pausedAt", OffsetDateTime.now().toString());
                if (configMapper.writeAutoConfig(json.writeValueAsString(value)) != 1) throw new IllegalStateException("Auto config update failed");
                if (accountCode != null) accountMapper.recordHealth(accountCode, "challenge", false, java.time.LocalDateTime.now());
                if (accountCode != null && accountMapper.pauseAccount(accountCode, reason) != 1) throw new IllegalStateException("Snap account not found: " + accountCode);
                purchases.pauseQueued();
            } catch (java.io.IOException ex) {
                throw new IllegalStateException("Invalid snap auto config", ex);
            }
        });
        Runnable evict = () -> TenantHelper.dynamic("1001", () ->
            Objects.requireNonNull(cacheManager.getCache(CacheNames.SYS_CONFIG)).evictIfPresent(AUTO_CONFIG_KEY));
        // Evict through the framework cache manager: both Redisson local cache and shared Redis.
        // Only after commit, so another reader cannot repopulate the old value before commit.
        // evictIfPresent is immediate: evict would register another afterCommit too late.
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { evict.run(); }
            });
        } else { evict.run(); }
    }
}
