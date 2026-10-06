package org.dromara.gz.jp.domain.vo;
import java.util.List;
/** Explicit outbound allowlist: no raw JSON or seller profile leaks. 303 supplies referencePriceCent. */
public record GzJpSnapSearchVO(String query, String redirectItemId, List<Card> items, boolean hasNext, boolean stale) {
    public record Card(String externalId, String titleJa, String titleZh, long priceJpy, Long referencePriceCent, String thumbUrl) { }
}
