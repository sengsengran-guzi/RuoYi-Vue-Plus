package org.dromara.gz.jp.service.internal;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.service.ConfigService;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.gz.jp.domain.entity.*;
import org.dromara.gz.jp.mapper.*;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Read only tenant scoped projection; never probes the remote gateway. */
@Service @RequiredArgsConstructor
public class GzJpSnapPurchaseReadService {
    private final GzJpSnapPurchaseMapper tasks;
    private final GzJpSnapAccountMapper accounts;
    private final GzJpOrderItemMapper items;
    private final ConfigService config;
    private final ObjectMapper json;
    public record Detail(String shippingPayer, String refundStatus, Long refundedAmountCent) {}
    public record Summary(boolean autoEnabled, String pausedReason, long accountCount, Map<String,Long> healthCounts, long manualCount, long reminderCount) {}
    public TableDataInfo<GzJpSnapPurchase> automatic(PageQuery page) {
        return TableDataInfo.build(tasks.selectPage(page.build(), new LambdaQueryWrapper<GzJpSnapPurchase>()
            .in(GzJpSnapPurchase::getPurchaseStatus,List.of("queued","executing","unknown"))
            .orderByAsc(GzJpSnapPurchase::getId)));
    }
    public Detail detail(Long id) {
        var task=tasks.selectById(id);
        if(task==null) throw new org.dromara.common.core.exception.ServiceException("代切任务不存在",404);
        var item=items.selectById(task.getOrderItemId());
        if(item==null) throw new IllegalStateException("Missing snap order item");
        try { return new Detail(json.readTree(item.getProductSnapshotJson()).path("snap").path("shippingPayer").asText(),tasks.shippingRefundStatus(item.getId()),tasks.shippingRefundAmount(item.getId())); }
        catch(java.io.IOException e) { throw new IllegalStateException("Invalid snap snapshot",e); }
    }
    public Summary summary(long reminders) {
        var rows=accounts.selectList(new LambdaQueryWrapper<GzJpSnapAccount>());
        boolean enabled=false; String reason=null;
        String raw=config.getConfigValue("gz.jp.snap.auto");
        if(raw!=null && !raw.isBlank()) {
            try {var value=json.readTree(raw); enabled=value.path("enabled").asBoolean(); reason=value.path("pausedReason").asText(null);}
            catch(java.io.IOException e) {throw new IllegalStateException("Invalid snap auto configuration",e);}
        }
        return new Summary(enabled,reason,rows.size(),rows.stream().collect(Collectors.groupingBy(a -> a.getHealthStatus()==null ? "unknown" : a.getHealthStatus(),Collectors.counting())),
            tasks.selectCount(new LambdaQueryWrapper<GzJpSnapPurchase>().eq(GzJpSnapPurchase::getPurchaseStatus,"manual_pending")),reminders);
    }
}
