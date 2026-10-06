package org.dromara.gz.jp.mapper;

import org.apache.ibatis.annotations.Insert;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.gz.jp.domain.entity.GzJpSnapItem;

/**
 * gz_jp_snap_item 数据层（GZ-SNAP-102 建；业务查询由使用它的下游卡按需追加）。
 *
 * <p>多租户由 {@code TenantLineInnerInterceptor} 自动 append {@code WHERE tenant_id = ?}；
 * 自定义 SQL 若带 JOIN / 子查询须手写补齐 tenant_id 相等条件（拦截器不下探子查询）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-SNAP-102)
 */
public interface GzJpSnapItemMapper extends BaseMapperPlus<GzJpSnapItem, GzJpSnapItem> {
    @org.apache.ibatis.annotations.Select("SELECT * FROM gz_jp_snap_item WHERE platform='mercari' AND external_id=#{externalId} AND del_flag='0' FOR UPDATE")
    GzJpSnapItem lockExternal(@org.apache.ibatis.annotations.Param("externalId") String externalId);
    @org.apache.ibatis.annotations.Update("UPDATE gz_jp_snap_item SET active_order_item_id=#{orderItemId},version=version+1,update_time=NOW() WHERE id=#{id} AND del_flag='0'")
    int occupy(@org.apache.ibatis.annotations.Param("id") Long id, @org.apache.ibatis.annotations.Param("orderItemId") Long orderItemId);
    @org.apache.ibatis.annotations.Update("UPDATE gz_jp_snap_item SET active_order_item_id=NULL,version=version+1,update_time=NOW() WHERE active_order_item_id=#{orderItemId} AND del_flag='0'")
    int releaseSnapLock(@org.apache.ibatis.annotations.Param("orderItemId") Long orderItemId);
    @Insert("INSERT INTO gz_jp_snap_item (platform,external_id,title_ja,title_zh,price_jpy,item_status,shipping_payer,category_id,condition_text,seller_id,gate_result,gate_reason,raw_json,fetched_at,create_time,update_time) "
        + "VALUES (#{platform},#{externalId},#{titleJa},#{titleZh},#{priceJpy},#{itemStatus},#{shippingPayer},#{categoryId},#{conditionText},#{sellerId},#{gateResult},#{gateReason},#{rawJson},#{fetchedAt},NOW(),NOW()) "
        + "ON DUPLICATE KEY UPDATE title_ja=VALUES(title_ja),title_zh=VALUES(title_zh),price_jpy=VALUES(price_jpy),item_status=VALUES(item_status),shipping_payer=VALUES(shipping_payer),category_id=VALUES(category_id),condition_text=VALUES(condition_text),seller_id=VALUES(seller_id),gate_result=VALUES(gate_result),gate_reason=VALUES(gate_reason),raw_json=VALUES(raw_json),fetched_at=VALUES(fetched_at),update_time=NOW(),version=version+1")
    int upsertLive(GzJpSnapItem item);
}
