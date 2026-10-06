package org.dromara.gz.jp.service.internal;
import lombok.RequiredArgsConstructor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.tenant.helper.TenantHelper;
import org.dromara.gz.jp.domain.bo.GzJpSnapSubmitBo;
import org.dromara.gz.jp.domain.dto.*;
import org.dromara.gz.jp.domain.entity.*;
import org.dromara.gz.jp.domain.vo.*;
import org.dromara.gz.jp.mapper.*;
import org.dromara.gz.common.pay.domain.bo.CreateOrderBo;
import org.dromara.gz.common.pay.domain.entity.GzPayTransaction;
import org.dromara.gz.common.pay.domain.vo.MpPayParamsVO;
import org.dromara.gz.common.pay.enums.PayBusinessType;
import org.dromara.gz.common.pay.mapper.GzPayTransactionMapper;
import org.dromara.gz.common.pay.service.IGzPayTransactionService;
import org.dromara.gz.common.pay.service.internal.*;
import org.dromara.gz.common.service.IGzUserService;
import org.dromara.gz.user.service.IGzUserAddressService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import static org.dromara.gz.jp.exception.GzJpSnapSubmitErrorCode.*;
@Service @RequiredArgsConstructor
public class GzJpSnapSubmitService {
    private final GzJpSnapQuoteService quotes;
    private final GzJpSnapReadService reads;
    private final GzJpSnapItemMapper items;
    private final GzJpOrderMapper orders;
    private final GzJpOrderItemMapper orderItems;
    private final IGzUserAddressService addresses;
    private final IGzUserService users;
    private final IGzPayTransactionService payments;
    private final GzPayTransactionMapper transactions;
    private final IWechatPayClient wechat;
    private final PayAppidResolver appids;
    private final PayOrderNoGenerator numbers;
    private final ObjectMapper json;
    private final TransactionTemplate tx;
    private final GzJpSnapImageService images;
    public GzJpOrderSubmitVO submit(GzJpSnapSubmitBo bo,Long userId) {
        if (userId==null) throw new ServiceException("未登录",401);
        if (!Boolean.TRUE.equals(bo.getAckNoCancel())) throw error("请勾选不可取消说明",SNAP_ACK_REQUIRED);
        var quote=TenantHelper.dynamic("1001",()->quotes.require(bo.getQuoteId()));
        // This call owns its own tenant scope and upserts live cache before we acquire any DB lock.
        var live=reads.item(quote.externalId());
        validateLive(quote,live);
        return TenantHelper.dynamic("1001",()->tx.execute(status->submitLocked(bo,userId,quote,live)));
    }
    static void validateLive(GzJpSnapQuoteSnapshot quote,GzJpSnapDetailVO live) {
        if (!"on_sale".equals(live.itemStatus())) throw error("商品已售出或下架",SNAP_SOLD_OUT);
        if (live.priceJpy()>quote.priceJpy() || !Objects.equals(live.shippingPayer(),quote.shippingPayer())) throw error("价格或运费承担有变化，请重新报价",SNAP_PRICE_CHANGED);
        if ("blocked".equals(live.gateResult())) throw error("此商品暂不可购买",SNAP_NOT_PURCHASABLE);
    }
    GzJpOrderSubmitVO submitLocked(GzJpSnapSubmitBo bo,Long userId,GzJpSnapQuoteSnapshot prior,GzJpSnapDetailVO live) {
        var quote=quotes.require(bo.getQuoteId(),prior.externalId());
        var item=items.lockExternal(quote.externalId());
        if (item==null) throw error("商品已下架",SNAP_SOLD_OUT);
        if (item.getActiveOrderItemId()!=null) {
            // The immutable order_id is a locator only; state decisions use current reads.
            // All writers take order before order item, matching onPaid and avoiding lock inversion.
            var locator=orderItems.selectById(item.getActiveOrderItemId());
            var order=locator==null?null:orders.lockById(locator.getOrderId());
            var held=order==null?null:orderItems.lockById(item.getActiveOrderItemId());
            if (order!=null && held!=null && java.util.Set.of("created","paid").contains(order.getBusinessStatus()) && !"purchase_failed".equals(held.getFulfillStatus())) {
                if (!Objects.equals(order.getUserId(),userId)) throw error("商品正在被其他客人购买",SNAP_ITEM_LOCKED);
                if ("paid".equals(order.getBusinessStatus())) throw error("您已购买此商品",SNAP_ALREADY_BOUGHT);
                return response(order,pay(order,userId));
            }
        }
        var address=addresses.getByIdForUser(userId,bo.getAddressId());
        if (address==null) throw new ServiceException("收货地址无效，请重新选择",4103);
        GzJpOrder order=new GzJpOrder();
        order.setOrderNo(numbers.generate(PayBusinessType.JP)); order.setUserId(userId); order.setTotalAmountCent(quote.totalCent());
        order.setBusinessStatus("created"); order.setVersion(0); order.setUserNote(bo.getUserNote());
        order.setAddressSnapshotJson(write(JpOrderSnapshot.Address.builder().recipient(address.getRecipientName()).mobile(address.getMobile()).province(address.getProvince()).city(address.getCity()).district(address.getDistrict()).detail(address.getDetail()).build()));
        orders.insert(order);
        String sourceImage=null;
        try { var raw=json.readTree(item.getRawJson()).path("image_urls"); if(raw.isArray() && !raw.isEmpty()) sourceImage=raw.get(0).asText(); }
        catch (java.io.IOException ex) { throw new ServiceException("商品图片快照无法读取",ex); }
        var snap=JpOrderSnapshot.Snap.builder().externalId(quote.externalId()).titleJa(live.titleJa()).titleZh(live.titleZh())
            .priceJpy(quote.priceJpy()).ratePer100Jpy(quote.ratePer100jpy()).markupRatio(quote.markupRatio()).goodsCent(quote.goodsCent())
            .fixedFeeCent(quote.fixedFeeCent()).estShippingCent(quote.estShippingCent()).totalCent(quote.totalCent()).expiresAt(quote.expiresAt())
            .gateResult("manual".equals(live.gateResult()) ? "manual" : quote.gateResult()).gateReason(live.gateReason()).shippingPayer(quote.shippingPayer()).conditionText(live.conditionText())
            .mainImageUrl(sourceImage).quoteId(bo.getQuoteId()).ackNoCancel(true)
            .quotedAt(Instant.ofEpochMilli(quote.quotedAt()).atZone(ZoneId.of("Asia/Shanghai")).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))).build();
        var row=new GzJpOrderItem(); row.setOrderId(order.getId());row.setUserId(userId);row.setSource("snap");row.setSnapItemId(item.getId());
        row.setQty(1); row.setUnitPriceCent(quote.totalCent());row.setAmountCent(quote.totalCent());row.setFulfillStatus("purchasing");row.setVersion(0);
        row.setProductSnapshotJson(write(JpOrderSnapshot.Product.builder().snap(snap).build()));
        GzJpOrderItemSourceInvariant.check(row);orderItems.insert(row);
        if(items.occupy(item.getId(),row.getId())!=1) throw new ServiceException("商品锁写入失败");
        var params=pay(order,userId);
        images.afterCommit(row.getId(),sourceImage);
        return response(order,params);
    }
    private MpPayParamsVO pay(GzJpOrder order,Long userId) {
        var pending=transactions.selectOne(Wrappers.<GzPayTransaction>lambdaQuery().eq(GzPayTransaction::getBusinessType,"jp")
            .eq(GzPayTransaction::getBusinessOrderNo,order.getOrderNo()).eq(GzPayTransaction::getStatus,"pending")
            .gt(GzPayTransaction::getExpireTime,LocalDateTime.now()).orderByDesc(GzPayTransaction::getId).last("LIMIT 1"));
        if(pending!=null && pending.getPrepayId()!=null) {
            var p=wechat.buildPayParams(pending.getPrepayId(),appids.resolveForCurrentApp());
            return MpPayParamsVO.builder().timeStamp(p.timeStamp()).nonceStr(p.nonceStr()).packageVal(p.packageVal()).signType(p.signType()).paySign(p.paySign()).outTradeNo(pending.getOutTradeNo()).build();
        }
        var user=users.selectVoById(userId);if(user==null || user.getOpenid()==null) throw new ServiceException("用户信息异常，请重新登录",401);
        return payments.createBusinessOrder(CreateOrderBo.builder().businessType(PayBusinessType.JP).businessOrderNo(order.getOrderNo()).amountCent(order.getTotalAmountCent()).userId(userId).openid(user.getOpenid()).description("谷子宇宙代切").build());
    }
    private static GzJpOrderSubmitVO response(GzJpOrder o,MpPayParamsVO p) { return GzJpOrderSubmitVO.builder().orderId(o.getId()).orderNo(o.getOrderNo()).totalAmountCent(o.getTotalAmountCent()).itemCount(1).payParams(p).build(); }
    private String write(Object value) { try{return json.writeValueAsString(value);}catch(java.io.IOException ex){throw new ServiceException("订单快照无法序列化",ex);} }
    private static ServiceException error(String message,int code) {return new ServiceException(message,code);}
}
