package org.dromara.gz.jp.domain.vo;
/** Public price breakdown. FX and markup belong exclusively to server snapshots. */
public record GzJpSnapPriceVO(long goodsCent, long fixedFeeCent, long estShippingCent, long totalCent,
    long priceJpy, String quoteId, long expiresAt) { }
