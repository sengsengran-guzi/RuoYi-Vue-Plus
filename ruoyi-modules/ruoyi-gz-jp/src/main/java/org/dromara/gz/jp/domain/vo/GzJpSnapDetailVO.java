package org.dromara.gz.jp.domain.vo;
import java.util.List;
/** 303 can decorate this fresh projection with admission / quote, never reuse stored raw_json as live authority. */
public record GzJpSnapDetailVO(String externalId, String titleJa, String titleZh, String descriptionJa, String descriptionZh,
    long priceJpy, String itemStatus, String shippingPayer, String conditionText, String categoryId, List<String> imageUrls,
    boolean purchasable, String unavailableReason, Long referencePriceCent) { }
