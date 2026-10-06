package org.dromara.gz.jp.service.internal;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;
import java.util.HashSet;
import java.util.Set;
@Component
public class GzJpSnapFilter {
    public record Rules(Set<String> categories, Set<String> keywords) { }
    public Rules rules(JsonNode config) {
        Set<String> cats = new HashSet<>(); Set<String> words = new HashSet<>();
        config.path("categoryWhitelist").forEach(v -> { if (!v.asText().isBlank()) cats.add(v.asText()); });
        config.path("keywordBlacklist").forEach(v -> { if (!v.asText().isBlank()) words.add(v.asText()); });
        return new Rules(Set.copyOf(cats), Set.copyOf(words));
    }
    public String reason(JsonNode item, Rules rules) {
        if ("shops".equals(item.path("item_type").asText())) return "shops_unsupported";
        if (!"on_sale".equals(item.path("status").asText())) return "item_not_on_sale";
        boolean allowed = rules.categories().contains(item.path("category_id").asText());
        for (JsonNode id : item.path("category_lineage")) allowed |= rules.categories().contains(id.asText());
        if (!allowed) return "category_not_allowed";
        String title = item.path("title_ja").asText();
        for (String word : rules.keywords()) if (title.contains(word)) return "keyword_blocked:" + word;
        return null;
    }
}
