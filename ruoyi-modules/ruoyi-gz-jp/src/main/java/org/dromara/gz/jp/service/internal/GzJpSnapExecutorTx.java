package org.dromara.gz.jp.service.internal;
import com.fasterxml.jackson.databind.*;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.service.ConfigService;
import org.dromara.gz.jp.domain.entity.*;
import org.dromara.gz.jp.service.internal.dto.GzJpRefundPlan;
import org.dromara.gz.jp.mapper.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;
/** Short transactions; HTTP always happens after commit. */
@Service @RequiredArgsConstructor
public class GzJpSnapExecutorTx {
 private final GzJpSnapPurchaseMapper tasks;
 private final GzJpSnapAccountMapper accounts;
 private final GzJpSnapItemMapper snaps;
 private final GzJpOrderMapper orders;
 private final GzJpOrderItemMapper items;
 private final GzJpRefundTxService refunds;
 private final ConfigService config;
 private final GzJpSnapRuntimeConfigMapper runtime;
 private final ObjectMapper json;
 public boolean enabled(){try{String r=config.getConfigValue("gz.jp.snap.auto");return r!=null&&!r.isBlank()&&json.readTree(r).path("enabled").asBoolean(false);}catch(java.io.IOException e){throw new IllegalStateException("Invalid snap config",e);}}
 @Transactional(rollbackFor=Exception.class)
 public GzJpSnapPurchase claim(Long id,LocalDateTime now){
  try {String raw=runtime.lockAutoConfig();if(raw==null||!json.readTree(raw).path("enabled").asBoolean(false))return null;}catch(java.io.IOException e){throw new IllegalStateException("Invalid auto config",e);}
  var pool=accounts.lockAccounts();var t=tasks.lock(id);
  if(t==null||!"queued".equals(t.getPurchaseStatus()))return null;
  LocalDate date=LocalDate.now(ZoneId.of("Asia/Tokyo"));LocalDateTime earliest=null;
  for(var a:pool){
   if(!Boolean.TRUE.equals(a.getAdminEnabled())||!"healthy".equals(a.getHealthStatus())||(a.getPauseReason()!=null&&!a.getPauseReason().isBlank()))continue;
   if((date.equals(a.getTodayDate())?a.getTodayCount():0)>=a.getDailyLimit())continue;
   LocalDateTime ready=a.getLastPurchaseAt()==null?now:a.getLastPurchaseAt().plusSeconds(a.getCooldownSec());
   if(tasks.executing(a.getAccountCode())>0&&ready.isBefore(now.plusSeconds(5)))ready=now.plusSeconds(5);
   if(ready.isAfter(now)){if(earliest==null||ready.isBefore(earliest))earliest=ready;continue;}
   if(tasks.claim(id,t.getVersion(),a.getAccountCode(),now)!=1)return null;
   if(accounts.consume(a.getId(),date,now)!=1)throw new IllegalStateException("Account allocation lost");
   t.setAccountCode(a.getAccountCode());t.setPurchaseStatus("executing");t.setExecutedAt(now);return t;
  }
  if(earliest!=null)t.setNextActionAt(earliest);else{t.setPurchaseStatus("manual_pending");t.setManualReason("no_account");t.setNextActionAt(null);}save(t);return null;
 }
 @Transactional(rollbackFor=Exception.class)
 public GzJpSnapPurchase claimReconcile(Long id,LocalDateTime now){var t=tasks.lock(id);if(t==null||!"unknown".equals(t.getPurchaseStatus())||t.getNextActionAt()==null||t.getNextActionAt().isAfter(now))return null;t.setNextActionAt(now.plusMinutes(1));save(t);return t;}
 public String shipping(GzJpSnapPurchase t){try{return json.readTree(items.selectById(t.getOrderItemId()).getProductSnapshotJson()).path("snap").path("shippingPayer").asText();}catch(java.io.IOException e){throw new IllegalStateException("Invalid shipping snapshot",e);}}
 @Transactional(rollbackFor=Exception.class)
 public GzJpRefundPlan result(GzJpSnapPurchase original,JsonNode r,boolean reconcile,String evidence,String error){
  // Lock hierarchy shared with submit/cancel: product snapshot -> order -> item -> task.
  snaps.lockSnapById(original.getSnapItemId());var read=items.selectById(original.getOrderItemId());
  if(read==null)throw new IllegalStateException("Missing snap item");orders.lockById(read.getOrderId());var item=items.lockById(read.getId());var t=tasks.lock(original.getId());
  if(t==null||!(reconcile?"unknown":"executing").equals(t.getPurchaseStatus()))return null;
  LocalDateTime now=LocalDateTime.now();if(evidence!=null&&!evidence.isBlank())t.setEvidenceFileIds(evidence);if(error!=null)t.setLastError(error.substring(0,Math.min(error.length(),512)));
  boolean success=r!=null&&(reconcile?r.path("found").asBoolean(false):"success".equals(r.path("result").asText()));
  if(success&&!r.path("mercari_txn_id").asText().isBlank()&&r.path("actual_price_jpy").asLong()>0){
   t.setPurchaseStatus("success");t.setTradeStatus("await_ship");t.setMercariTxnId(r.path("mercari_txn_id").asText());t.setActualPriceJpy(r.path("actual_price_jpy").asLong());t.setResultAt(now);t.setNextActionAt(null);
   if(!"purchasing".equals(item.getFulfillStatus())||items.advanceGuarded(List.of(item.getId()),"purchasing","await_seller_ship",null)!=1)throw new IllegalStateException("Snap success transition lost");save(t);return null;
  }
  if(reconcile){int count=t.getReconcileCount()+1;t.setReconcileCount(count);if(count>=3){t.setPurchaseStatus("manual_pending");t.setManualReason(r!=null&&r.hasNonNull("item_status")&&!"on_sale".equals(r.path("item_status").asText())?"reconcile_sold":"reconcile_exhausted");t.setNextActionAt(null);}else t.setNextActionAt(now.plusMinutes(count==1?2:10));save(t);return null;}
  String reason=r==null?"":r.path("reason").asText();
  if(r!=null&&"failed".equals(r.path("result").asText())&&Set.of("sold_out","deleted","price_up").contains(reason)){
   t.setPurchaseStatus("failed");t.setFailReason(reason);t.setResultAt(now);t.setNextActionAt(null);save(t);
   var plan=refunds.markFailedAndCreateRefunds(List.of(item.getId()),"代切购买失败: "+reason,null,"snap_scheduler");if(!plan.getRejects().isEmpty()||!plan.getRefundRejects().isEmpty())throw new IllegalStateException("Snap refund rejected");snaps.releaseSnapLock(item.getId());return plan;
  }
  if(r!=null&&"failed".equals(r.path("result").asText())&&Set.of("shipping_changed","account_unpayable").contains(reason)){t.setPurchaseStatus("manual_pending");t.setManualReason(reason);t.setNextActionAt(null);}
  else{t.setPurchaseStatus("unknown");t.setNextActionAt(now.plusSeconds(30));}save(t);return null;
 }
 private void save(GzJpSnapPurchase t){if(tasks.saveOutcome(t)!=1)throw new IllegalStateException("Snap task version conflict");t.setVersion(t.getVersion()+1);}
}
