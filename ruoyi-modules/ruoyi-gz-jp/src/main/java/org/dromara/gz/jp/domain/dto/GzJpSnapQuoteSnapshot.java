package org.dromara.gz.jp.domain.dto;
import java.math.BigDecimal;
/** Server-only immutable price terms for 304 order snapshot; never serialize this into mp responses. */
public record GzJpSnapQuoteSnapshot(String externalId, long priceJpy, BigDecimal ratePer100jpy,
    BigDecimal markupRatio, long goodsCent, long fixedFeeCent, long estShippingCent, long totalCent,
    long quotedAt, long expiresAt, String gateResult, String shippingPayer) { }
