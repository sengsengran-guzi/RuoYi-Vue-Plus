package org.dromara.gz.jp.service.internal;

import com.fasterxml.jackson.databind.JsonNode;
import org.dromara.gz.jp.domain.enums.GzJpSnapGateResult;
import org.springframework.stereotype.Component;

/** Ordered admission; live item fields remain the authority, never the stored raw_json. */
@Component
public class GzJpSnapGate {
    public record Decision(GzJpSnapGateResult result, String reason, boolean purchasable, String unavailableReason) { }
    public Decision evaluate(JsonNode item, String intro, JsonNode rules, GzJpSnapFilter.Rules filterRules) {
        String title = item.path("title_ja").asText();
        String description = item.path("description_ja").asText();
        String reason = null;
        for (JsonNode word : rules.path("blockKeywords")) {
            if (!word.asText().isBlank() && title.contains(word.asText())) { reason = "reserved:" + word.asText(); break; }
        }
        if (reason == null) reason = new GzJpSnapFilter().admissionReason(item, filterRules);
        if (reason == null) reason = match(rules.path("proxyRefuseKeywords"), title, description, intro, "proxy_refuse:");
        GzJpSnapGateResult result;
        if (reason != null) result = GzJpSnapGateResult.BLOCKED;
        else {
            reason = match(rules.path("manualKeywords"), title, description, "", "comment_required:");
            if (reason == null && "buyer".equals(item.path("shipping_payer").asText())) reason = "buyer_shipping";
            result = reason == null ? GzJpSnapGateResult.AUTO : GzJpSnapGateResult.MANUAL;
        }
        boolean onSale = "on_sale".equals(item.path("status").asText());
        boolean purchasable = onSale && result != GzJpSnapGateResult.BLOCKED;
        return new Decision(result, reason, purchasable, !onSale ? "item_not_on_sale" : result == GzJpSnapGateResult.BLOCKED ? reason : null);
    }
    private String match(JsonNode words, String title, String description, String intro, String prefix) {
        for (JsonNode value : words) {
            String word = value.asText(); if (word.isBlank()) continue;
            if (title.contains(word)) return prefix + word + "@title";
            if (description.contains(word)) return prefix + word + "@description";
            if (intro != null && intro.contains(word)) return prefix + word + "@seller_intro";
        }
        return null;
    }
}
