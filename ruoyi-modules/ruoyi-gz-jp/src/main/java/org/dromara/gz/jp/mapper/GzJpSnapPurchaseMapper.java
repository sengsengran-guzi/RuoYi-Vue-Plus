package org.dromara.gz.jp.mapper;

import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.gz.jp.domain.entity.GzJpSnapPurchase;

/**
 * gz_jp_snap_purchase 数据层（GZ-SNAP-102 建；业务查询由使用它的下游卡按需追加）。
 *
 * <p>多租户由 {@code TenantLineInnerInterceptor} 自动 append {@code WHERE tenant_id = ?}；
 * 自定义 SQL 若带 JOIN / 子查询须手写补齐 tenant_id 相等条件（拦截器不下探子查询）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-SNAP-102)
 */
public interface GzJpSnapPurchaseMapper extends BaseMapperPlus<GzJpSnapPurchase, GzJpSnapPurchase> {
    @org.apache.ibatis.annotations.Insert("INSERT INTO gz_jp_snap_purchase(order_item_id,order_no,snap_item_id,external_id,locked_price_jpy,mode,purchase_status,manual_reason,next_action_at,reconcile_count,trade_status,version,create_time,update_time) VALUES(#{orderItemId},#{orderNo},#{snapItemId},#{externalId},#{lockedPriceJpy},#{mode},#{purchaseStatus},#{manualReason},#{nextActionAt},0,'none',0,NOW(),NOW()) ON DUPLICATE KEY UPDATE order_item_id=order_item_id")
    int insertIfAbsent(GzJpSnapPurchase task);
}
