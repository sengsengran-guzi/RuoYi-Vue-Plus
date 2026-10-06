package org.dromara.gz.jp.job;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.tenant.helper.TenantHelper;
import org.dromara.gz.jp.domain.entity.GzJpSnapPurchase;
import org.dromara.gz.jp.mapper.GzJpSnapPurchaseMapper;
import org.dromara.gz.jp.service.internal.*;
import org.dromara.gz.jp.service.impl.GzJpRefundServiceImpl;
import org.dromara.gz.jp.snap.gateway.*;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import java.time.LocalDateTime;
import java.util.Map;
@Slf4j @Component @RequiredArgsConstructor

public class GzJpSnapPurchaseJob {
 private final GzJpSnapExecutorTx tx;
 private final GzJpSnapPurchaseMapper tasks;
 private final GzJpGatewayClient gateway;
 private final GzJpSnapRiskControl risk;
 private final GzJpSnapEvidenceService evidence;
 private final GzJpRefundServiceImpl refunds;
 private final org.dromara.gz.jp.mapper.GzJpSnapAccountMapper accounts;
 @org.springframework.beans.factory.annotation.Value("${gz.local-scheduler.enabled:false}") private boolean schedulerEnabled;
 @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
 public void recoverAfterRestart(){TenantHelper.dynamic("1001",()->tasks.recover(LocalDateTime.now().minusSeconds(60)));}
 private final java.util.concurrent.Semaphore slots=new java.util.concurrent.Semaphore(2);
 @Scheduled(fixedDelay=5000,initialDelay=5000)
 public void tick(){
  if(!schedulerEnabled)return;
  TenantHelper.dynamic("1001",()->{
   tasks.recover(LocalDateTime.now().minusSeconds(60));
   for(var t:tasks.due(LocalDateTime.now())){
    if(!slots.tryAcquire())break;
    boolean submitted=false;
    try{
     if("unknown".equals(t.getPurchaseStatus())){
      var claimed=tx.claimReconcile(t.getId(),LocalDateTime.now());if(claimed==null)continue;submitted=true;
      try{gateway.reconcile(claimed.getOrderNo(),claimed.getExternalId(),claimed.getAccountCode()).whenComplete((r,e)->complete(claimed,r,e,true));}catch(Exception e){complete(claimed,null,e,true);}
     }else if(tx.enabled()){
      var claimed=tx.claim(t.getId(),LocalDateTime.now());if(claimed==null)continue;submitted=true;
      try{gateway.purchase(Map.of("order_no",claimed.getOrderNo(),"external_id",claimed.getExternalId(),"max_price_jpy",claimed.getLockedPriceJpy(),"account_code",claimed.getAccountCode(),"expect_shipping_payer",tx.shipping(claimed))).whenComplete((r,e)->complete(claimed,r,e,false));}catch(Exception e){complete(claimed,null,e,false);}
     }
    }catch(Exception e){log.error("Snap scheduler task failed id={}",t.getId(),e);}
    finally{if(!submitted)slots.release();}
   }
  });
 }
 private void complete(GzJpSnapPurchase t,JsonNode r,Throwable e,boolean reconcile){
  final boolean[] pause={false};
  TenantHelper.dynamic("1001",()->{
   try{
    if(e!=null)log.error("Snap gateway {} failed order={}",reconcile?"reconcile":"purchase",t.getOrderNo(),e);
    if(r!=null&&"account_unpayable".equals(r.path("reason").asText()))log.error("Snap account payment unavailable account={} order={}",t.getAccountCode(),t.getOrderNo());
    var plan=tx.result(t,r,reconcile,evidence.store(r),e==null?null:e.toString());
    if(r!=null&&"account_unpayable".equals(r.path("reason").asText()))accounts.pauseAccount(t.getAccountCode(),"account_unpayable: balance or payment unavailable");
    if(plan!=null)refunds.dispatchSnapRefundPlan(plan,java.util.List.of(t.getOrderItemId()));
    if(!reconcile){var recent=tasks.recentResults(t.getAccountCode());pause[0]=(r!=null&&r.path("risk_signal").asBoolean(false))||(recent.size()==3&&recent.stream().allMatch("unknown"::equals));}
   }catch(Exception ex){log.error("Snap result persistence failed order={}; recovery will reconcile",t.getOrderNo(),ex);}
  });
  // RiskControl has its own tenant scope; do not nest it in the processing scope.
  try{if(pause[0])risk.pause(t.getAccountCode(),"purchase:risk_or_three_unknown");}finally{slots.release();}
 }
}
