package org.dromara.gz.jp.service.internal;
import lombok.RequiredArgsConstructor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.service.ConfigService;
import org.dromara.gz.jp.domain.dto.JpOrderSnapshot;
import org.dromara.gz.jp.domain.entity.*;
import org.dromara.gz.jp.mapper.GzJpSnapPurchaseMapper;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;
@Service @RequiredArgsConstructor
public class GzJpSnapPaidTaskService {
    private final GzJpSnapPurchaseMapper tasks;
    private final ConfigService config;
    private final ObjectMapper json;
    /** Only invoked after created -> paid affected=1, inside the payment callback transaction. No gateway call. */
    public void enqueue(GzJpOrder order, GzJpOrderItem item) {
        try {
            var snap=json.readValue(item.getProductSnapshotJson(), JpOrderSnapshot.Product.class).getSnap();
            if (snap==null || snap.getPriceJpy()==null || snap.getExternalId()==null) throw new ServiceException("代切订单快照不完整");
            String raw=config.getConfigValue("gz.jp.snap.auto");
            boolean enabled=raw!=null && json.readTree(raw).path("enabled").asBoolean(false);
            boolean manual="manual".equals(snap.getGateResult()) || !enabled;
            String reason="manual".equals(snap.getGateResult()) ? "gate:"+(snap.getGateReason()==null?"manual":snap.getGateReason()) : "auto_paused";
            // Task reason is VARCHAR(64); retain the full admission evidence in the order snapshot.
            if (reason.codePointCount(0,reason.length())>64) reason=reason.substring(0,reason.offsetByCodePoints(0,64));
            var task=GzJpSnapPurchase.builder().orderItemId(item.getId()).orderNo(order.getOrderNo()).snapItemId(item.getSnapItemId())
                .externalId(snap.getExternalId()).lockedPriceJpy(snap.getPriceJpy()).mode(manual?"manual":"auto")
                .purchaseStatus(manual?"manual_pending":"queued").manualReason(manual?reason:null)
                .nextActionAt(manual?null:LocalDateTime.now()).reconcileCount(0).tradeStatus("none").version(0).build();
            tasks.insertIfAbsent(task);
        } catch (java.io.IOException ex) { throw new ServiceException("代切任务快照或配置无法读取",ex); }
    }
}
