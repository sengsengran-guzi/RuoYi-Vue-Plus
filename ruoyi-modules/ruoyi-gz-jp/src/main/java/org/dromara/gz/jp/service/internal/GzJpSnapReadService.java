package org.dromara.gz.jp.service.internal;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.service.ConfigService;
import org.dromara.common.tenant.helper.TenantHelper;
import org.dromara.gz.jp.config.GzJpSnapSearchProperties;
import org.dromara.gz.jp.domain.entity.GzJpSnapItem;
import org.dromara.gz.jp.domain.entity.GzJpSnapTerm;
import org.dromara.gz.jp.domain.vo.GzJpSnapSearchVO;
import org.dromara.gz.jp.domain.vo.GzJpSnapDetailVO;
import org.dromara.gz.jp.mapper.GzJpSnapItemMapper;
import org.dromara.gz.jp.mapper.GzJpSnapTermMapper;
import org.dromara.gz.jp.snap.gateway.*;
import org.springframework.stereotype.Service;
import java.net.URI;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.LinkedHashMap;
import static org.dromara.gz.jp.exception.GzJpSnapSearchErrorCode.*;
@Slf4j @Service @RequiredArgsConstructor
public class GzJpSnapReadService {
    private final GzJpGatewayClient gateway;
    private final GzJpSnapNormalizer normalizer;
    private final GzJpSnapFilter filter;
    private final GzJpSnapTranslator translator;
    private final GzJpSnapReadCache cache;
    private final ConfigService config;
    private final GzJpSnapTermMapper terms;
    private final GzJpSnapItemMapper items;
    private final GzJpSnapSearchProperties properties;
    private final GzJpSnapRiskControl risk;
    private final ObjectMapper json;
    private final GzJpSnapGate gate;
    private final GzJpSnapQuoteService quotes;
    public GzJpSnapSearchVO search(String input, String sort, int page) {
        try { return TenantHelper.dynamic("1001", () -> searchInTenant(input, sort, page)); }
        catch (GzJpGatewayException ex) { upstreamFailure(ex); throw unavailable(); }
    }
    GzJpSnapSearchVO searchInTenant(String input, String sort, int page) {
        if (!Set.of("created_desc", "price_asc", "price_desc").contains(sort) || page < 1 || page > 100) throw new ServiceException("搜索排序或页码无效", SNAP_INVALID_QUERY);
        var normalized = normalizer.normalize(input, enabledTerms());
        if (normalized.redirectItemId() != null) return new GzJpSnapSearchVO(null, normalized.redirectItemId(), List.of(), false, false);
        JsonNode rulesJson = configJson(); var rules = filter.rules(rulesJson);
        if (rules.categories().isEmpty()) return new GzJpSnapSearchVO(normalized.query(), null, List.of(), false, false);
        String categories = String.join(",", rules.categories().stream().sorted().toList());
        String key = "search:" + GzJpSnapTranslator.hash(normalized.query() + "\n" + sort + "\n" + page + "\n" + categories);
        JsonNode entry = cached(key);
        if (fresh(entry)) return cards(normalized.query(), entry.path("data"), rules, false, page);
        try (AutoCloseable ignored = cache.lock(key)) {
            entry = cached(key);
            if (fresh(entry)) return cards(normalized.query(), entry.path("data"), rules, false, page);
            JsonNode result;
            try { result = gateway.search(normalized.query(), sort, page, categories); }
            catch (GzJpGatewayException ex) {
                // Risk writes own tenant scope, so do not nest it inside this one.
                if (ex.getHttpStatus() == 401 || ex.getHttpStatus() == 403) throw ex;
                if (entry != null) return cards(normalized.query(), entry.path("data"), rules, true, page);
                throw unavailable();
            }
            ObjectNode value = json.createObjectNode().put("at", System.currentTimeMillis()); value.set("data", result);
            // Three-minute fresh cache plus a bounded 24-hour fallback copy, not an immortal catalogue.
            cache.put(key, value.toString(), Duration.ofMinutes(3));
            cache.put(key + ":stale", value.toString(), Duration.ofHours(24));
            return cards(normalized.query(), result, rules, false, page);
        } catch (ServiceException | GzJpGatewayException ex) { throw ex; }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw unavailable(); }
        catch (Exception ex) { log.warn("Snap search coordination unavailable: {}", ex.getClass().getSimpleName()); throw unavailable(); }
    }
    private GzJpSnapSearchVO cards(String query, JsonNode body, GzJpSnapFilter.Rules rules, boolean stale, int page) {
        List<GzJpSnapSearchVO.Card> result = new ArrayList<>();
        Map<String, String> titleSources = new LinkedHashMap<>();
        if (page == 1) for (JsonNode item : body.path("items")) {
            if (filter.reason(item, rules) == null) titleSources.put(item.path("external_id").asText(), item.path("title_ja").asText());
        }
        List<String> externalIds = new ArrayList<>();
        for (JsonNode item : body.path("items")) if (filter.reason(item, rules) == null) externalIds.add(item.path("external_id").asText());
        Map<String, GzJpSnapItem> known = new LinkedHashMap<>();
        if (!externalIds.isEmpty()) {
            for (GzJpSnapItem snapshot : items.selectList(new LambdaQueryWrapper<GzJpSnapItem>()
                .eq(GzJpSnapItem::getPlatform, "mercari").in(GzJpSnapItem::getExternalId, externalIds))) {
                known.put(snapshot.getExternalId(), snapshot);
            }
        }
        Map<String, String> titleTranslations = page == 1 ? translator.titles(titleSources) : Map.of();
        for (JsonNode item : body.path("items")) {
            if (filter.reason(item, rules) != null) continue;
            String id = item.path("external_id").asText(); String title = item.path("title_ja").asText();
            String translated = titleTranslations.getOrDefault(id, title);
            GzJpSnapItem snapshot = known.get(id);
            result.add(new GzJpSnapSearchVO.Card(id, title, translated, item.path("price_jpy").asLong(), quotes.reference(item.path("price_jpy").asLong(), item.path("shipping_payer").asText()),
                image(item.path("thumb_url").asText(item.path("thumb").asText())),
                snapshot == null ? null : snapshot.getGateResult(), snapshot == null ? null : snapshot.getGateReason()));
        }
        return new GzJpSnapSearchVO(query, null, List.copyOf(result), body.path("has_next").asBoolean(), stale);
    }
    /** Always calls gateway once; callers (303/304) must invoke this for fresh live authority. */
    public GzJpSnapDetailVO item(String id) {
        if (id == null || !id.matches("m[0-9]+")) throw new ServiceException("暂不支持煤炉 Shops 商品", SNAP_SHOPS_UNSUPPORTED);
        try { return TenantHelper.dynamic("1001", () -> itemInTenant(id)); }
        catch (GzJpGatewayException ex) {
            if (ex.getHttpStatus() == 404) return new GzJpSnapDetailVO(id, "", "", "", "", 0, "deleted", "", "", "", List.of(), false, "item_deleted", null);
            upstreamFailure(ex); throw unavailable();
        }
    }
    GzJpSnapDetailVO itemInTenant(String id) {
        JsonNode live = gateway.item(id);
        if (!id.equals(live.path("external_id").asText()) || live.path("price_jpy").asLong() <= 0
            || !Set.of("on_sale", "trading", "sold_out", "deleted").contains(live.path("status").asText())
            || !Set.of("seller", "buyer").contains(live.path("shipping_payer").asText())) throw unavailable();
        String title = live.path("title_ja").asText(); String titleZh = translator.translate(title, "ja", "zh", "title:" + id);
        String description = live.path("description_ja").asText();
        var decision = gate.evaluate(live, sellerIntro(live.path("seller_id").asText()), gateJson(), filter.rules(configJson()));
        String reason = decision.reason();
        GzJpSnapItem entity = GzJpSnapItem.builder().platform("mercari").externalId(id).titleJa(title).titleZh(titleZh.length() > 255 ? titleZh.substring(0, 255) : titleZh)
            .priceJpy(live.path("price_jpy").asLong()).itemStatus(live.path("status").asText()).shippingPayer(live.path("shipping_payer").asText())
            .categoryId(live.path("category_id").asText()).conditionText(live.path("condition_text").asText()).sellerId(live.path("seller_id").asText())
            .gateResult(decision.result().getCode()).gateReason(reason == null || reason.length() <= 64 ? reason : reason.substring(0, 64)).rawJson(live.toString()).fetchedAt(LocalDateTime.now()).build();
        items.upsertLive(entity);
        List<String> pictures = new ArrayList<>(); live.path("image_urls").forEach(v -> { String url = image(v.asText()); if (!url.isBlank()) pictures.add(url); });
        org.dromara.gz.jp.domain.vo.GzJpSnapPriceVO price = null;
        Integer quoteError = null;
        if (decision.purchasable()) {
            try { price = quotes.quote(id, entity.getPriceJpy(), decision.result().getCode(), entity.getShippingPayer()); }
            catch (ServiceException ex) {
                if (ex.getCode() == null || (ex.getCode() != 4121 && ex.getCode() != 4122)) throw ex;
                quoteError = ex.getCode();
                log.warn("Snap detail quote refused for {}: {}", id, ex.getCode());
            }
        }
        return new GzJpSnapDetailVO(id, title, titleZh, description, translator.translate(description, "ja", "zh", "description:" + id),
            entity.getPriceJpy(), entity.getItemStatus(), entity.getShippingPayer(), entity.getConditionText(), entity.getCategoryId(),
            List.copyOf(pictures), decision.purchasable() && price != null, decision.unavailableReason() != null ? decision.unavailableReason() : quoteError != null ? "quote_unavailable" : null,
            price == null ? null : price.totalCent(), decision.result().getCode(), reason, price, quoteError, entity.getFetchedAt().atZone(java.time.ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli());
    }
    public List<String> hotTerms() { return TenantHelper.dynamic("1001", () -> enabledTerms().stream().filter(t -> "ip".equals(t.getTermType())).limit(12).map(GzJpSnapTerm::getZhTerm).toList()); }
    public GzJpSnapNormalizer.Result normalize(String input) {
        try { return TenantHelper.dynamic("1001", () -> normalizer.normalize(input, enabledTerms())); }
        catch (GzJpGatewayException ex) { upstreamFailure(ex); throw unavailable(); }
    }
    private List<GzJpSnapTerm> enabledTerms() { return terms.selectList(new LambdaQueryWrapper<GzJpSnapTerm>().eq(GzJpSnapTerm::getEnabled, true).orderByAsc(GzJpSnapTerm::getSortNo).orderByAsc(GzJpSnapTerm::getId)); }
    private JsonNode gateJson() {
        String raw = config.getConfigValue("gz.jp.snap.gate");
        try {
            JsonNode value = raw == null ? null : json.readTree(raw);
            if (value == null || !value.path("blockKeywords").isArray() || !value.path("proxyRefuseKeywords").isArray() || !value.path("manualKeywords").isArray())
                throw new ServiceException("准入配置未完整设置", SNAP_UPSTREAM_DOWN);
            return value;
        } catch (java.io.IOException ex) { log.error("Invalid snap gate config; refusing purchase", ex); throw unavailable(); }
    }
    private String sellerIntro(String sellerId) {
        if (sellerId.isBlank()) { log.warn("Snap seller ID unavailable; using item-only admission"); return ""; }
        String key = "seller:" + sellerId;
        String stored = cache.get(key);
        if (stored != null) return stored;
        try {
            JsonNode seller = gateway.seller(sellerId);
            if (seller == null || !sellerId.equals(seller.path("seller_id").asText()) || !seller.path("intro").isTextual()) {
                log.warn("Snap seller intro unavailable for {}; using item-only admission", sellerId); return "";
            }
            String intro = seller.path("intro").asText(); cache.put(key, intro, Duration.ofHours(24)); return intro;
        } catch (GzJpGatewayException ex) {
            log.warn("Snap seller lookup failed for {} ({}); using item-only admission", sellerId, ex.getKind());
            return "";
        }
    }
    private JsonNode configJson() {
        String raw = config.getConfigValue("gz.jp.snap.filter");
        try { return raw == null || raw.isBlank() ? json.createObjectNode() : json.readTree(raw); }
        catch (java.io.IOException ex) { log.warn("Invalid snap filter configuration; refusing all categories"); return json.createObjectNode(); }
    }
    private JsonNode cached(String key) {
        String value = cache.get(key); if (value == null) value = cache.get(key + ":stale"); if (value == null) return null;
        try { return json.readTree(value); } catch (java.io.IOException ex) { log.warn("Invalid snap search cache; refetching"); return null; }
    }
    private boolean fresh(JsonNode entry) { return entry != null && System.currentTimeMillis() - entry.path("at").asLong() < 180000; }
    public String image(String source) {
        try {
            URI uri = URI.create(source);
            if (!Set.of("static.mercdn.net").contains(uri.getHost()) || !"https".equals(uri.getScheme())) return "";
            String path = uri.getRawPath();
            if (path == null || path.contains("..") || path.contains("%") || path.contains("\\")) return "";
            return properties.getImageBaseUrl().replaceAll("/+$", "") + "/v1/img" + path;
        } catch (IllegalArgumentException ex) { return ""; }
    }
    private void upstreamFailure(GzJpGatewayException ex) {
        if (ex.getHttpStatus() == 401 || ex.getHttpStatus() == 403) risk.pause(null, "snap_read_http_" + ex.getHttpStatus());
    }
    private ServiceException unavailable() { return new ServiceException("煤炉暂时访问不了", SNAP_UPSTREAM_DOWN); }
}
