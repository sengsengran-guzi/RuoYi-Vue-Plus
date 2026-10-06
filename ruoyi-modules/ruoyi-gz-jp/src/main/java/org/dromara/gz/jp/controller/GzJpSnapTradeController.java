package org.dromara.gz.jp.controller;
import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.Valid;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.idempotent.annotation.RepeatSubmit;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.dromara.common.mybatis.core.page.*;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.gz.jp.domain.entity.GzJpSnapPurchase;
import org.dromara.gz.jp.service.internal.GzJpSnapTradeService;
import org.springframework.web.bind.annotation.*;

@RestController @RequiredArgsConstructor @RequestMapping("/system/gz/jp/snap/trade")
public class GzJpSnapTradeController extends org.dromara.common.web.core.BaseController {
    private final GzJpSnapTradeService service;
    public record ArrivalInput(@PositiveOrZero Long actualShippingJpy) {}
    @GetMapping("/list") @SaCheckPermission("gz:jp:snap:purchase:query")
    public TableDataInfo<GzJpSnapPurchase> list(@RequestParam(required=false) String tradeStatus, PageQuery page) { return service.list(tradeStatus,page); }
    @GetMapping("/reminder-count") @SaCheckPermission("gz:jp:snap:purchase:query")
    public R<Long> reminders() { return R.ok(service.reminderCount()); }
    @PostMapping("/{id}/seller-shipped") @SaCheckPermission("gz:jp:snap:purchase:trade") @RepeatSubmit
    @Log(title="代切卖家发货",businessType=BusinessType.UPDATE)
    public R<Void> shipped(@PathVariable Long id) { service.transition(id,"shipped",null,LoginHelper.getUserId()); return R.ok(); }
    @PostMapping("/{id}/trade-cancel") @SaCheckPermission("gz:jp:snap:purchase:trade") @RepeatSubmit
    @Log(title="代切交易取消",businessType=BusinessType.UPDATE)
    public R<Void> cancel(@PathVariable Long id) { service.transition(id,"cancelled",null,LoginHelper.getUserId()); return R.ok(); }
    @PostMapping("/{id}/arrived") @SaCheckPermission("gz:jp:snap:purchase:trade") @RepeatSubmit
    @Log(title="代切到仓",businessType=BusinessType.UPDATE)
    public R<Void> arrived(@PathVariable Long id,@Valid @RequestBody(required=false) ArrivalInput input) { service.transition(id,"arrived",input==null?null:input.actualShippingJpy(),LoginHelper.getUserId()); return R.ok(); }
    @PostMapping("/{id}/rated") @SaCheckPermission("gz:jp:snap:purchase:trade") @RepeatSubmit
    @Log(title="代切评价",businessType=BusinessType.UPDATE)
    public R<Void> rated(@PathVariable Long id) { service.transition(id,"rated",null,LoginHelper.getUserId()); return R.ok(); }
    @PostMapping("/{id}/returned") @SaCheckPermission("gz:jp:snap:purchase:trade") @RepeatSubmit
    @Log(title="代切退货完成",businessType=BusinessType.UPDATE)
    public R<Void> returned(@PathVariable Long id) { service.transition(id,"returned",null,LoginHelper.getUserId()); return R.ok(); }
}
