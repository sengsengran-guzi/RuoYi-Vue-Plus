package org.dromara.gz.jp.domain.vo;
/** Authenticated, read-only public projection of an existing quote; no private pricing terms. */
public record GzJpSnapQuotePreviewVO(String externalId, String titleZh, String titleJa, GzJpSnapPriceVO price) { }
