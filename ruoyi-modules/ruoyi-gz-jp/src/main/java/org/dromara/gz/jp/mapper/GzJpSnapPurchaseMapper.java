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

    @org.apache.ibatis.annotations.Select("SELECT * FROM gz_jp_snap_purchase WHERE tenant_id='1001' AND del_flag='0' AND purchase_status IN ('queued','unknown') AND next_action_at<=#{now} ORDER BY id LIMIT 32")
    java.util.List<GzJpSnapPurchase> due(java.time.LocalDateTime now);
    @org.apache.ibatis.annotations.Select("SELECT * FROM gz_jp_snap_purchase WHERE id=#{id} AND del_flag='0' FOR UPDATE")
    GzJpSnapPurchase lock(Long id);
    @org.apache.ibatis.annotations.Update("UPDATE gz_jp_snap_purchase SET purchase_status='executing',account_code=#{code},executed_at=#{now},next_action_at=NULL,version=version+1,update_time=#{now} WHERE id=#{id} AND purchase_status='queued' AND version=#{version} AND del_flag='0'")
    int claim(@org.apache.ibatis.annotations.Param("id") Long id,@org.apache.ibatis.annotations.Param("version") Integer version,@org.apache.ibatis.annotations.Param("code") String code,@org.apache.ibatis.annotations.Param("now") java.time.LocalDateTime now);
    @org.apache.ibatis.annotations.Update("UPDATE gz_jp_snap_purchase SET purchase_status='unknown',next_action_at=NOW(),last_error='restart: execution outcome unknown',version=version+1,update_time=NOW() WHERE tenant_id='1001' AND del_flag='0' AND purchase_status='executing' AND executed_at<#{cutoff}")
    int recover(java.time.LocalDateTime cutoff);
    @org.apache.ibatis.annotations.Update("UPDATE gz_jp_snap_purchase SET purchase_status='manual_pending',manual_reason='auto_paused',next_action_at=NULL,version=version+1,update_time=NOW() WHERE tenant_id='1001' AND del_flag='0' AND purchase_status='queued'")
    int pauseQueued();
    @org.apache.ibatis.annotations.Select("SELECT COUNT(*) FROM gz_jp_snap_purchase WHERE tenant_id='1001' AND del_flag='0' AND account_code=#{code} AND purchase_status='executing'")
    int executing(String code);
    @org.apache.ibatis.annotations.Select("SELECT purchase_status FROM gz_jp_snap_purchase WHERE tenant_id='1001' AND del_flag='0' AND account_code=#{code} AND executed_at IS NOT NULL ORDER BY executed_at DESC,id DESC LIMIT 3")
    java.util.List<String> recentResults(String code);

    @org.apache.ibatis.annotations.Update("UPDATE gz_jp_snap_purchase SET purchase_status=#{purchaseStatus},manual_reason=#{manualReason},fail_reason=#{failReason},reconcile_count=#{reconcileCount},next_action_at=#{nextActionAt},mercari_txn_id=#{mercariTxnId},actual_price_jpy=#{actualPriceJpy},trade_status=#{tradeStatus},result_at=#{resultAt},evidence_file_ids=COALESCE(#{evidenceFileIds},evidence_file_ids),last_error=COALESCE(#{lastError},last_error),version=version+1,update_time=NOW() WHERE id=#{id} AND version=#{version} AND del_flag='0'")
    int saveOutcome(GzJpSnapPurchase task);
}
