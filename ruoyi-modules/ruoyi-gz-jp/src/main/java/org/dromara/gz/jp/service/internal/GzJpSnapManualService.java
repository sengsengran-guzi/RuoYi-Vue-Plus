package org.dromara.gz.jp.service.internal;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.dromara.gz.jp.mapper.*;
import org.dromara.gz.jp.domain.entity.GzJpSnapPurchase;
import org.dromara.gz.jp.service.impl.GzJpRefundServiceImpl;
import org.springframework.stereotype.Service;
import java.util.List;
@Service @RequiredArgsConstructor
public class GzJpSnapManualService {
 private final GzJpSnapPurchaseMapper tasks;
 private final GzJpOrderItemMapper items;
 private final GzJpSnapExecutorTx tx;
 private final GzJpRefundServiceImpl refunds;
 private final ObjectMapper json;
 public record ManualRow(Long id,Long orderItemId,String orderNo,String manualReason,String originalUrl,Long lockedPriceJpy,long estShippingCent,String evidenceFileIds){}
 public List<ManualRow> list(){return tasks.manualList().stream().map(t->{
  try{var snap=json.readTree(items.selectById(t.getOrderItemId()).getProductSnapshotJson()).path("snap");
   return new ManualRow(t.getId(),t.getOrderItemId(),t.getOrderNo(),t.getManualReason(),"https://jp.mercari.com/item/"+t.getExternalId(),t.getLockedPriceJpy(),snap.path("estShippingCent").asLong(),t.getEvidenceFileIds());
  }catch(java.io.IOException e){throw new IllegalStateException("Invalid snap snapshot",e);}
 }).toList();}
 public void success(Long id,String txn,Long price,Long operator){tx.manual(id,txn,price,null,null,operator);}
 public void fail(Long id,String reason,String note,Long operator){var plan=tx.manual(id,null,null,reason,note,operator);if(plan!=null)refunds.dispatchSnapRefundPlan(plan,List.of(tasks.selectById(id).getOrderItemId()));}
}
