package org.dromara.gz.jp.controller;
import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.idempotent.annotation.RepeatSubmit;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.gz.jp.service.internal.GzJpSnapManualService;
import org.springframework.web.bind.annotation.*;
import java.util.List;
@RestController @RequiredArgsConstructor @RequestMapping("/system/gz/jp/snap/purchase")
public class GzJpSnapPurchaseController extends org.dromara.common.web.core.BaseController {
 private final GzJpSnapManualService service;
 private final org.dromara.gz.jp.service.internal.GzJpSnapPurchaseReadService reads;
 private final org.dromara.gz.jp.service.internal.GzJpSnapTradeService trades;
 @GetMapping("/automatic-list") @SaCheckPermission("gz:jp:snap:purchase:query")
 public org.dromara.common.mybatis.core.page.TableDataInfo<org.dromara.gz.jp.domain.entity.GzJpSnapPurchase> automatic(org.dromara.common.mybatis.core.page.PageQuery page){return reads.automatic(page);}
 @GetMapping("/summary") @SaCheckPermission("gz:jp:snap:purchase:query")
 public R<org.dromara.gz.jp.service.internal.GzJpSnapPurchaseReadService.Summary> summary(){return R.ok(reads.summary(trades.reminderCount()));}
 @GetMapping("/{id}/detail") @SaCheckPermission("gz:jp:snap:purchase:query")
 public R<org.dromara.gz.jp.service.internal.GzJpSnapPurchaseReadService.Detail> detail(@PathVariable Long id){return R.ok(reads.detail(id));}
 public record SuccessInput(@NotBlank @Size(max=64) String mercariTxnId,@NotNull @Positive Long actualPriceJpy){}
 public record FailInput(@NotBlank @Pattern(regexp="sold_out|deleted|price_up|manual_rejected") String failReason,@Size(max=512) String note){}
 @GetMapping("/manual-list") @SaCheckPermission("gz:jp:snap:purchase:query")
 public R<List<GzJpSnapManualService.ManualRow>> list(){return R.ok(service.list());}
 @PostMapping("/{id}/manual-success") @SaCheckPermission("gz:jp:snap:purchase:manual") @RepeatSubmit
 @Log(title="代切人工成功",businessType=BusinessType.UPDATE)
 public R<Void> success(@PathVariable Long id,@Valid @RequestBody SuccessInput input){service.success(id,input.mercariTxnId(),input.actualPriceJpy(),LoginHelper.getUserId());return R.ok();}
 @PostMapping("/{id}/manual-fail") @SaCheckPermission("gz:jp:snap:purchase:manual") @RepeatSubmit
 @Log(title="代切人工失败",businessType=BusinessType.UPDATE)
 public R<Void> fail(@PathVariable Long id,@Valid @RequestBody FailInput input){service.fail(id,input.failReason(),input.note(),LoginHelper.getUserId());return R.ok();}
}
