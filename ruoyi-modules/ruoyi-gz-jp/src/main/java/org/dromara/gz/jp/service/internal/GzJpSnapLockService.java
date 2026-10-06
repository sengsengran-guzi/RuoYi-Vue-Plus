package org.dromara.gz.jp.service.internal;
import lombok.RequiredArgsConstructor;
import org.dromara.gz.jp.mapper.GzJpSnapItemMapper;
import org.dromara.gz.jp.mapper.GzJpOrderItemMapper;
import org.dromara.gz.jp.domain.entity.GzJpOrderItem;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
@Service @RequiredArgsConstructor
public class GzJpSnapLockService {
    private final GzJpSnapItemMapper items;
    private final GzJpOrderItemMapper orderItems;
    /** Caller must finish its guarded cancellation/failure transition first, in this same transaction. */
    @Transactional(rollbackFor=Exception.class)
    public int releaseSnapLock(Long orderItemId) { return items.releaseSnapLock(orderItemId); }
    /** Cancellation takes snap before order, matching submit; locator fields are immutable. */
    public void lockOrderItemsForCancellation(Long orderId) {
        for (var item : orderItems.selectList(Wrappers.<GzJpOrderItem>lambdaQuery().eq(GzJpOrderItem::getOrderId,orderId).eq(GzJpOrderItem::getSource,"snap").orderByAsc(GzJpOrderItem::getSnapItemId))) {
            items.lockSnapById(item.getSnapItemId());
        }
    }
    public void releaseOrder(Long orderId) {
        for (var item : orderItems.selectList(Wrappers.<GzJpOrderItem>lambdaQuery().eq(GzJpOrderItem::getOrderId,orderId).eq(GzJpOrderItem::getSource,"snap"))) releaseSnapLock(item.getId());
    }
}
