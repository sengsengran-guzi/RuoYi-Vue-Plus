package org.dromara.gz.jp.service.internal;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.constant.CacheNames;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.redis.utils.RedisUtils;
import org.dromara.common.tenant.helper.TenantHelper;
import org.dromara.gz.jp.domain.entity.GzJpSnapAccount;
import org.dromara.gz.jp.job.GzJpSnapHealthJob;
import org.dromara.gz.jp.mapper.*;
import org.dromara.gz.jp.snap.gateway.GzJpSnapRiskControl;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.*;
import java.time.*;
import java.util.*;

/** Admin owns only enablement, limits and pause clearance. Heartbeat retains health ownership. */
@Service @RequiredArgsConstructor
public class GzJpSnapAccountAdminService {
    private final GzJpSnapAccountMapper accounts;
    private final GzJpSnapRuntimeConfigMapper configs;
    private final GzJpSnapRiskControl risk;
    private final ObjectMapper json;
    private final CacheManager cacheManager;
    public record Row(Long id,String accountCode,String nickname,Boolean adminEnabled,String healthStatus,
                      Integer todayCount,Integer dailyLimit,Integer cooldownSec,LocalDateTime lastHeartbeatAt,String pauseReason) {}
    public List<Row> list(String code) {
        return accounts.selectList(new LambdaQueryWrapper<GzJpSnapAccount>()
            .like(code != null && !code.isBlank(), GzJpSnapAccount::getAccountCode, code)
            .orderByAsc(GzJpSnapAccount::getId)).stream().map(GzJpSnapAccountAdminService::row).toList();
    }
    static Row row(GzJpSnapAccount a) {
        int today=LocalDate.now(ZoneId.of("Asia/Tokyo")).equals(a.getTodayDate()) ? Objects.requireNonNullElse(a.getTodayCount(),0) : 0;
        return new Row(a.getId(),a.getAccountCode(),a.getNickname(),a.getAdminEnabled(),a.getHealthStatus(),today,
            a.getDailyLimit(),a.getCooldownSec(),a.getLastHeartbeatAt(),a.getPauseReason());
    }
    public Long create(String code,String nickname,int limit,int cooldown) {
        if(accounts.selectCount(new LambdaQueryWrapper<GzJpSnapAccount>().eq(GzJpSnapAccount::getAccountCode,code))>0)
            throw new ServiceException("账号编码已存在 / Account code already exists");
        var a=GzJpSnapAccount.builder().accountCode(code).nickname(nickname).dailyLimit(limit).cooldownSec(cooldown)
            .adminEnabled(false).healthStatus("unreachable").todayCount(0).version(0).delFlag("0").build();
        accounts.insert(a);return a.getId();
    }
    public void edit(Long id,String nickname,int limit,int cooldown) { requireOne(accounts.editAdmin(id,nickname,limit,cooldown)); }
    public void enabled(Long id,boolean enabled) { requireOne(accounts.setAdminEnabled(id,enabled)); }
    public void clearPause(Long id) { requireOne(accounts.clearPause(id)); }
    private void requireOne(int count) {if(count!=1)throw new ServiceException("账号不存在 / Account not found");}
    public record AutoSwitch(boolean enabled,String pausedReason,String pausedAt,JsonNode probe) {}
    public AutoSwitch autoSwitch() {
        JsonNode value=parse(configs.readAutoConfig());
        String probe=RedisUtils.getCacheObject(GzJpSnapHealthJob.PROBE_REDIS_KEY);
        return new AutoSwitch(value.path("enabled").asBoolean(false),value.path("pausedReason").asText(null),
            value.path("pausedAt").asText(null),probe==null?null:parse(probe));
    }
    public void pause(String reason) { risk.pause(null,reason); }
    @Transactional(rollbackFor=Exception.class)
    public void resume(boolean confirmed) {
        if(!confirmed)throw new ServiceException("请确认恢复自动下单 / Confirm resuming automatic purchase");
        // Same lock hierarchy as the executor: config -> account pool. Never touches manual tasks.
        ObjectNode value=(ObjectNode)parse(configs.lockAutoConfig());
        var pool=accounts.lockAccounts();
        if(pool.stream().anyMatch(a -> "challenge".equals(a.getHealthStatus())))
            throw new ServiceException("账号仍处于风控验证，不能恢复 / An account still requires challenge verification");
        if(pool.stream().noneMatch(a -> Boolean.TRUE.equals(a.getAdminEnabled()) && "healthy".equals(a.getHealthStatus())
            && (a.getPauseReason()==null || a.getPauseReason().isBlank())))
            throw new ServiceException("至少需要一个健康、启用且未暂停的账号 / A healthy enabled account without a pause is required");
        value.put("enabled",true);value.putNull("pausedReason");value.putNull("pausedAt");
        if(configs.writeAutoConfig(value.toString())!=1)throw new IllegalStateException("Auto config update failed");
        // Bypass deferred evict: transaction synchronization is still active in afterCommit.
        Runnable evict=() -> TenantHelper.dynamic("1001",() -> Objects.requireNonNull(cacheManager.getCache(CacheNames.SYS_CONFIG))
            .evictIfPresent(GzJpSnapRiskControl.AUTO_CONFIG_KEY));
        if(TransactionSynchronizationManager.isSynchronizationActive())TransactionSynchronizationManager.registerSynchronization(
            new TransactionSynchronization(){@Override public void afterCommit(){evict.run();}});
        else evict.run();
    }
    private JsonNode parse(String raw) {
        if(raw==null)throw new IllegalStateException("Missing snap auto config");
        try{return json.readTree(raw);}catch(java.io.IOException e){throw new IllegalStateException("Invalid snap JSON",e);}
    }
}
