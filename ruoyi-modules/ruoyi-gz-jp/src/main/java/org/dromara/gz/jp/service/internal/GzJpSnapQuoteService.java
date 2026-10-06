package org.dromara.gz.jp.service.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.service.ConfigService;
import org.dromara.gz.jp.domain.dto.GzJpSnapQuoteSnapshot;
import org.dromara.gz.jp.domain.entity.GzJpFxRate;
import org.dromara.gz.jp.domain.vo.GzJpSnapPriceVO;
import org.dromara.gz.jp.mapper.GzJpFxRateMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;
import static org.dromara.gz.jp.exception.GzJpSnapQuoteErrorCode.*;

@Slf4j @Service @RequiredArgsConstructor
public class GzJpSnapQuoteService {
    private final ConfigService config;
    private final GzJpFxRateMapper rates;
    private final GzJpSnapQuoteStore store;
    private final ObjectMapper json;
    public record Pricing(BigDecimal markupRatio, long fixedFeeCent, long estShippingCent, int quoteTtlSec) { }
    public Pricing pricing() {
        try {
            String raw = config.getConfigValue("gz.jp.snap.pricing");
            JsonNode node = raw == null ? null : json.readTree(raw);
            if (node == null || !node.path("markupRatio").isNumber() || !node.path("fixedFeeCent").isIntegralNumber()
                || !node.path("estShippingCent").isIntegralNumber() || !node.path("fixedFeeCent").canConvertToLong()
                || !node.path("estShippingCent").canConvertToLong()) throw unset();
            if (node.has("quoteTtlSec") && (!node.path("quoteTtlSec").isIntegralNumber() || !node.path("quoteTtlSec").canConvertToInt())) throw unset();
            int ttl = node.has("quoteTtlSec") ? node.path("quoteTtlSec").asInt() : 600;
            Pricing pricing = new Pricing(node.path("markupRatio").decimalValue(), node.path("fixedFeeCent").asLong(), node.path("estShippingCent").asLong(), ttl);
            if (pricing.markupRatio().signum() < 0 || pricing.fixedFeeCent() < 0 || pricing.estShippingCent() < 0 || ttl <= 0) throw unset();
            return pricing;
        } catch (java.io.IOException ex) { log.warn("Invalid snap pricing configuration", ex); throw unset(); }
    }
    public GzJpFxRate latestFresh() {
        var rate = rates.selectOne(new LambdaQueryWrapper<GzJpFxRate>().orderByDesc(GzJpFxRate::getFetchedAt).orderByDesc(GzJpFxRate::getId).last("LIMIT 1"));
        LocalDateTime now = LocalDateTime.now();
        if (rate == null || rate.getFetchedAt() == null || rate.getFetchedAt().isBefore(now.minusHours(6))
            || rate.getFetchedAt().isAfter(now.plusMinutes(1)) || rate.getRatePer100jpy() == null || rate.getRatePer100jpy().signum() <= 0) {
            log.error("[jp-snap] FX missing or older than six hours; refusing quote");
            throw new ServiceException("汇率超过6小时未更新，暂不能报价", SNAP_FX_STALE);
        }
        return rate;
    }
    public GzJpSnapQuoteSnapshot calculate(String externalId, long priceJpy, String gate, String payer, Pricing pricing, BigDecimal rate, long at) {
        if (priceJpy <= 0 || rate == null || rate.signum() <= 0 || !java.util.Set.of("auto", "manual").contains(gate) || !java.util.Set.of("seller", "buyer").contains(payer)) throw unset();
        try {
            // rate is CNY/100JPY; conversion to cents cancels the factor 100 exactly.
            long goods = BigDecimal.valueOf(priceJpy).multiply(rate).multiply(BigDecimal.ONE.add(pricing.markupRatio())).setScale(0, RoundingMode.CEILING).longValueExact();
            long shipping = "manual".equals(gate) && "buyer".equals(payer) ? pricing.estShippingCent() : 0;
            long total = Math.addExact(Math.addExact(goods, pricing.fixedFeeCent()), shipping);
            return new GzJpSnapQuoteSnapshot(externalId, priceJpy, rate, pricing.markupRatio(), goods, pricing.fixedFeeCent(), shipping, total,
                at, Math.addExact(at, Math.multiplyExact((long) pricing.quoteTtlSec(), 1000)), gate, payer);
        } catch (ArithmeticException ex) { log.error("Snap quote overflow", ex); throw unset(); }
    }
    public GzJpSnapPriceVO quote(String externalId, long priceJpy, String gate, String payer) {
        Pricing pricing = pricing();
        var snapshot = calculate(externalId, priceJpy, gate, payer, pricing, latestFresh().getRatePer100jpy(), System.currentTimeMillis());
        String id = UUID.randomUUID().toString();
        try { store.put(id, json.writeValueAsString(snapshot), Duration.ofSeconds(pricing.quoteTtlSec())); }
        catch (java.io.IOException ex) { throw new IllegalStateException("Unable to serialize snap quote", ex); }
        return new GzJpSnapPriceVO(snapshot.goodsCent(), snapshot.fixedFeeCent(), snapshot.estShippingCent(), snapshot.totalCent(), priceJpy, id, snapshot.expiresAt());
    }
    public Long reference(long priceJpy, String payer) {
        try { var pricing = pricing(); return calculate("", priceJpy, "buyer".equals(payer) ? "manual" : "auto", payer, pricing, latestFresh().getRatePer100jpy(), System.currentTimeMillis()).totalCent(); }
        catch (ServiceException ex) {
            if (ex.getCode() != null && (ex.getCode() == SNAP_PRICING_UNSET || ex.getCode() == SNAP_FX_STALE)) return null;
            throw ex;
        }
    }
    /** 304 must call within tenant 1001 and compare fresh gateway state independently. */
    public GzJpSnapQuoteSnapshot require(String quoteId, String externalId) {
        if (quoteId == null || !quoteId.matches("[a-f0-9-]{36}")) throw expired();
        String raw = store.get(quoteId);
        if (raw == null) throw expired();
        try {
            var snapshot = json.readValue(raw, GzJpSnapQuoteSnapshot.class);
            if (snapshot.expiresAt() <= System.currentTimeMillis()) throw expired();
            if (!snapshot.externalId().equals(externalId)) throw new ServiceException("报价不属于此商品", SNAP_QUOTE_ITEM_MISMATCH);
            return snapshot;
        } catch (java.io.IOException ex) { log.warn("Corrupt snap quote; refusing it", ex); throw expired(); }
    }
    private ServiceException unset() { return new ServiceException("定价参数未配置完整，暂不能报价", SNAP_PRICING_UNSET); }
    private ServiceException expired() { return new ServiceException("报价已过期，请重新报价", SNAP_QUOTE_EXPIRED); }
}
