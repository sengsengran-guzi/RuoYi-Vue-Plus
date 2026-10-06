package org.dromara.gz.jp.service.internal;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.dromara.gz.jp.domain.entity.GzJpOrderItem;
import org.dromara.gz.jp.domain.entity.GzJpRefund;
import org.dromara.gz.jp.domain.entity.GzJpSnapPurchase;
import org.dromara.gz.jp.mapper.GzJpRefundMapper;
import org.dromara.gz.jp.mapper.GzJpSnapPurchaseMapper;
import org.springframework.stereotype.Service;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 本人订单校验后的批量公共投影，不向 mp 下发采购任务实体。 */
@Service
@RequiredArgsConstructor
public class GzJpSnapOrderDisplayService {
    private final GzJpSnapPurchaseMapper purchases;
    private final GzJpRefundMapper refunds;
    private static final Set<String> TRADES = Set.of("none", "await_ship", "shipped", "arrived", "rated", "returned", "cancelled");
    public record Display(String status, String trade, long shippingRefunded) { }

    public Map<Long, Display> load(List<GzJpOrderItem> items) {
        var ids = items.stream().filter(i -> "snap".equals(i.getSource())).map(GzJpOrderItem::getId).toList();
        if (ids.isEmpty()) return Map.of();
        Map<Long, GzJpSnapPurchase> tasks = new HashMap<>();
        for (var task : purchases.selectList(Wrappers.<GzJpSnapPurchase>lambdaQuery().in(GzJpSnapPurchase::getOrderItemId, ids))) {
            tasks.put(task.getOrderItemId(), task);
        }
        Map<Long, Long> shipping = new HashMap<>();
        for (var refund : refunds.selectList(Wrappers.<GzJpRefund>lambdaQuery().in(GzJpRefund::getOrderItemId, ids)
            .eq(GzJpRefund::getRefundType, "shipping_diff").eq(GzJpRefund::getStatus, "refunded"))) {
            shipping.merge(refund.getOrderItemId(), refund.getRefundAmountCent(), Math::addExact);
        }
        Map<Long, Display> result = new HashMap<>();
        for (var item : items) {
            if ("snap".equals(item.getSource())) result.put(item.getId(), project(tasks.get(item.getId()), shipping.getOrDefault(item.getId(), 0L)));
        }
        return result;
    }

    public static Display project(GzJpSnapPurchase task, long shippingRefunded) {
        String status = "purchasing";
        String trade = task == null || !TRADES.contains(task.getTradeStatus() == null ? "" : task.getTradeStatus()) ? "none" : task.getTradeStatus();
        if (task != null) {
            if ("cancelled".equals(trade) || "seller_cancel".equals(task.getFailReason())) status = "trade_cancelled";
            else if ("returned".equals(trade) || "inspect_ng".equals(task.getFailReason())) status = "inspection_failed";
            else if ("failed".equals(task.getPurchaseStatus())) status = "not_purchased";
            else if ("manual_pending".equals(task.getPurchaseStatus())) status = "manual_processing";
            else if ("success".equals(task.getPurchaseStatus())) status = "purchased";
        }
        return new Display(status, trade, shippingRefunded);
    }
}
