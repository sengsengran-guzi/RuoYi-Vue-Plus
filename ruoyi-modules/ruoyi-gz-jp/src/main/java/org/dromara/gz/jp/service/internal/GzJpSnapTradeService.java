package org.dromara.gz.jp.service.internal;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.gz.jp.domain.entity.GzJpSnapPurchase;
import org.dromara.gz.jp.mapper.GzJpSnapPurchaseMapper;
import org.dromara.gz.jp.service.impl.GzJpRefundServiceImpl;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

@Service @RequiredArgsConstructor
public class GzJpSnapTradeService {
    private final GzJpSnapTradeTx tx;
    private final GzJpSnapPurchaseMapper tasks;
    private final GzJpRefundServiceImpl refunds;
    public void transition(Long id, String target, Long shipping, Long operator) {
        var plan = tx.transition(id, target, shipping, operator);
        if (plan != null) refunds.dispatchSnapRefundPlan(plan, "arrived".equals(target) ? List.of() : List.of(tasks.selectById(id).getOrderItemId()));
    }
    public TableDataInfo<GzJpSnapPurchase> list(String status, PageQuery page) {
        if (status != null && !status.isBlank() && !Set.of("await_ship","shipped","arrived","rated","returned","cancelled").contains(status))
            throw new ServiceException("非法交易状态筛选",409);
        return TableDataInfo.build(tasks.selectPage(page.build(),new LambdaQueryWrapper<GzJpSnapPurchase>()
            .eq(GzJpSnapPurchase::getPurchaseStatus,"success")
            .eq(status != null && !status.isBlank(),GzJpSnapPurchase::getTradeStatus,status)
            .last("ORDER BY CASE WHEN trade_status='arrived' AND rate_deadline_at <= DATE_ADD(NOW(), INTERVAL 12 HOUR) THEN 0 ELSE 1 END, rate_deadline_at IS NULL, rate_deadline_at ASC, id ASC")));
    }
    public long reminderCount() {
        return tasks.selectCount(new LambdaQueryWrapper<GzJpSnapPurchase>()
            .eq(GzJpSnapPurchase::getTradeStatus,"arrived")
            .le(GzJpSnapPurchase::getRateDeadlineAt,LocalDateTime.now().plusHours(12)));
    }
}
