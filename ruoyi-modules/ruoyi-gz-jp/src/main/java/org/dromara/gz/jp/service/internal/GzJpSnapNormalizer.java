package org.dromara.gz.jp.service.internal;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.gz.jp.domain.entity.GzJpSnapTerm;
import org.dromara.gz.jp.snap.gateway.GzJpGatewayClient;
import org.springframework.stereotype.Component;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import static org.dromara.gz.jp.exception.GzJpSnapSearchErrorCode.*;
@Component @RequiredArgsConstructor
public class GzJpSnapNormalizer {
    private final GzJpGatewayClient gateway;
    private final GzJpSnapTranslator translator;
    private static final Pattern ID = Pattern.compile("m[0-9]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern LINK = Pattern.compile("https?://jp\\.mercari\\.com/item/(m[0-9]+)(?:[/?#]|$)", Pattern.CASE_INSENSITIVE);
    private static final Pattern SHORT = Pattern.compile("https?://merc\\.li/[a-zA-Z0-9_-]+(?:[/?#][^\\s]*)?", Pattern.CASE_INSENSITIVE);
    public record Result(String query, String redirectItemId) { }
    public Result normalize(String input, List<GzJpSnapTerm> terms) {
        String q = input == null ? "" : input.trim();
        if (q.isBlank() || q.length() > 500) throw new ServiceException("请输入关键词或煤炉商品链接", SNAP_INVALID_QUERY);
        if (q.toLowerCase(java.util.Locale.ROOT).contains("jp.mercari.com/shops/")) throw new ServiceException("暂不支持煤炉 Shops 商品", SNAP_SHOPS_UNSUPPORTED);
        if (ID.matcher(q).matches()) return new Result(null, q.toLowerCase(java.util.Locale.ROOT));
        var link = LINK.matcher(q); if (link.find()) return new Result(null, link.group(1).toLowerCase(java.util.Locale.ROOT));
        var shortLink = SHORT.matcher(q);
        if (shortLink.find()) {
            JsonNode resolved = gateway.resolve(shortLink.group());
            String id = resolved.path("external_id").asText();
            if (!ID.matcher(id).matches()) throw new ServiceException("暂不支持煤炉 Shops 商品", SNAP_SHOPS_UNSUPPORTED);
            return new Result(null, id);
        }
        List<GzJpSnapTerm> sorted = terms.stream().filter(t -> Boolean.TRUE.equals(t.getEnabled()) && t.getZhTerm() != null && !t.getZhTerm().isBlank())
            .sorted(Comparator.comparingInt((GzJpSnapTerm t) -> t.getZhTerm().length()).reversed()
                .thenComparing(t -> t.getSortNo() == null ? 0 : t.getSortNo())).toList();
        StringBuilder out = new StringBuilder(); StringBuilder rest = new StringBuilder();
        for (int pos = 0; pos < q.length();) {
            GzJpSnapTerm match = null;
            for (var term : sorted) if (q.startsWith(term.getZhTerm(), pos)) { match = term; break; }
            if (match == null) { rest.append(q.charAt(pos++)); continue; }
            appendRest(out, rest); out.append(match.getJaTerm()); pos += match.getZhTerm().length();
        }
        appendRest(out, rest);
        return new Result(out.toString().trim().replaceAll("\\s+", " "), null);
    }
    private void appendRest(StringBuilder out, StringBuilder rest) {
        if (rest.isEmpty()) return;
        String value = rest.toString();
        // Japanese inputs (including kanji) must remain unchanged. Only uncovered CJK without kana goes to zh→ja.
        if (value.matches("(?s).*[\\p{IsHan}].*") && !value.matches("(?s).*[\\p{IsHiragana}\\p{IsKatakana}].*")) {
            String stripped = value.trim();
            out.append(value.substring(0, value.indexOf(stripped))).append(translator.translate(stripped, "zh", "ja", "query"))
                .append(value.substring(value.indexOf(stripped) + stripped.length()));
        } else out.append(value);
        rest.setLength(0);
    }
}
