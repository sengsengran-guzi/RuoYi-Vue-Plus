package org.dromara.gz.jp.job;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.tenant.helper.TenantHelper;
import org.dromara.gz.jp.mapper.GzJpSnapPurchaseMapper;
import org.dromara.gz.jp.service.internal.*;
import org.dromara.gz.jp.snap.gateway.GzJpGatewayClient;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.stream.Collectors;

/** Local patrol: account HTTP outside DB transactions; failed reads never advance state. */
@Slf4j @Component @RequiredArgsConstructor
public class GzJpSnapTradeJob {
    private final GzJpSnapPurchaseMapper tasks;
    private final GzJpSnapTradeService service;
    private final GzJpSnapTradeTx tx;
    private final GzJpGatewayClient gateway;
    @org.springframework.beans.factory.annotation.Value("${gz.local-scheduler.enabled:false}") private boolean schedulerEnabled;
    private LocalDateTime nextPatrol=LocalDateTime.MIN;
    @Scheduled(fixedDelay=60000,initialDelay=60000)
    public synchronized void tick() {
        if (!schedulerEnabled || LocalDateTime.now().isBefore(nextPatrol)) return;
        TenantHelper.dynamic("1001",()->{
            try {
                nextPatrol=LocalDateTime.now().plusMinutes(tx.setting("patrolMinutes",20));
                var groups=tasks.patrolTrades().stream().filter(t -> t.getAccountCode()!=null && !t.getAccountCode().isBlank())
                    .collect(Collectors.groupingBy(t -> t.getAccountCode()));
                for (var entry:groups.entrySet()) {
                    try {
                        var trades=gateway.trades(entry.getKey()).path("trades");
                        if (!trades.isArray()) throw new IllegalStateException("Invalid trade patrol response");
                        for (var remote:trades) for (var task:entry.getValue()) {
                            if (!Objects.equals(task.getMercariTxnId(),remote.path("mercari_txn_id").asText())
                                || !Objects.equals(task.getExternalId(),remote.path("external_id").asText())) continue;
                            String status=remote.path("trade_status").asText();
                            if (!"shipped".equals(status) && !"cancelled".equals(status)) continue;
                            try { service.transition(task.getId(),status,null,null); }
                            catch (Exception e) { log.error("Snap trade patrol transition failed id={}",task.getId(),e); }
                        }
                    } catch (Exception e) { log.error("Snap trade patrol account failed account={}",entry.getKey(),e); }
                }
            } catch (Exception e) { log.error("Snap trade patrol failed",e); }
        });
    }
}
