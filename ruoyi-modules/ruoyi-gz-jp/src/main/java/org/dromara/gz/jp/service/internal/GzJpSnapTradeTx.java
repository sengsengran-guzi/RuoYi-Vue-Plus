package org.dromara.gz.jp.service.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.service.ConfigService;
import org.dromara.gz.jp.mapper.*;
import org.dromara.gz.jp.service.internal.dto.GzJpRefundPlan;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.Set;

/** Atomic local transition and monetary reservation. No gateway/payment HTTP inside transactions. */
@Service @RequiredArgsConstructor
public class GzJpSnapTradeTx {
    private final GzJpSnapPurchaseMapper tasks;
    private final GzJpSnapItemMapper snaps;
    private final GzJpOrderMapper orders;
    private final GzJpOrderItemMapper items;
    private final GzJpSnapExecutorTx executor;
    private final GzJpRefundTxService refunds;
    private final ConfigService config;
    private final ObjectMapper json;

    public int setting(String name, int fallback) {
        String raw = config.getConfigValue("gz.jp.snap.trade");
        if (raw == null || raw.isBlank()) return fallback;
        try {
            var value = json.readTree(raw).get(name);
            if (value == null) return fallback;
            if (!value.canConvertToInt() || value.asInt() <= 0) throw new ServiceException("非法代切交易配置: " + name);
            return value.asInt();
        } catch (java.io.IOException e) { throw new IllegalStateException("Invalid snap trade configuration", e); }
    }

    @Transactional(rollbackFor=Exception.class)
    public GzJpRefundPlan transition(Long id, String target, Long actualShippingJpy, Long operator) {
        var read = tasks.selectById(id);
        if (read == null) throw new ServiceException("代切交易不存在", 409);
        // Same hierarchy as submit, executor, cancel and refund reservation.
        snaps.lockSnapById(read.getSnapItemId());
        var itemRead = items.selectById(read.getOrderItemId());
        if (itemRead == null) throw new IllegalStateException("Missing snap order item");
        orders.lockById(itemRead.getOrderId());
        var item = items.lockById(itemRead.getId());
        var task = tasks.lock(id);
        if (task == null || !"success".equals(task.getPurchaseStatus())) throw new ServiceException("尚未成交", 409);
        String from = task.getTradeStatus();
        if (target.equals(from)) return null;
        boolean allowed = switch (target) {
            case "shipped" -> "await_ship".equals(from);
            case "cancelled" -> Set.of("await_ship", "shipped").contains(from);
            case "arrived" -> "shipped".equals(from);
            case "rated", "returned" -> "arrived".equals(from);
            default -> false;
        };
        if (!allowed) throw new ServiceException("SNAP_TRADE_STATE_CONFLICT: " + from + " → " + target, 409);
        boolean buyerShipping = false;
        LocalDateTime now = LocalDateTime.now();
        if ("arrived".equals(target)) {
            try { buyerShipping = "buyer".equals(json.readTree(item.getProductSnapshotJson()).path("snap").path("shippingPayer").asText()); }
            catch (java.io.IOException e) { throw new IllegalStateException("Invalid snap shipping snapshot", e); }
            if (buyerShipping && (actualShippingJpy == null || actualShippingJpy < 0)) throw new ServiceException("到付商品已到仓必须填写实际运费（日元）", 409);
            if (!buyerShipping && actualShippingJpy != null) throw new ServiceException("包邮商品无需填写实际运费", 409);
            task.setActualShippingJpy(actualShippingJpy);
            task.setArrivedAt(now);
            task.setRateDeadlineAt(now.plusHours(setting("rateDeadlineHours",24)));
        }
        if ("rated".equals(target)) task.setRatedAt(now);
        if ("cancelled".equals(target)) task.setFailReason("seller_cancel");
        if ("returned".equals(target)) task.setFailReason("inspect_ng");
        task.setTradeStatus(target);
        if (operator != null) task.setManualOperator(operator);
        if (tasks.saveTrade(task) != 1) throw new ServiceException("交易状态已变化，请刷新", 409);
        if ("cancelled".equals(target) || "returned".equals(target))
            return executor.tradeFailure(item, task.getFailReason(), operator, "snap_trade");
        if (buyerShipping) return refunds.createShippingDiff(item.getId(), actualShippingJpy);
        return null;
    }
}
