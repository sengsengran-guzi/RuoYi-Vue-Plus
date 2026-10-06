package org.dromara.gz.jp.domain.vo;
import java.util.List;
/** Fresh admission and public quote projection; no FX or markup terms are exposed. */
public record GzJpSnapDetailVO(String externalId, String titleJa, String titleZh, String descriptionJa, String descriptionZh,
    long priceJpy, String itemStatus, String shippingPayer, String conditionText, String categoryId, List<String> imageUrls,
    boolean purchasable, String unavailableReason, Long referencePriceCent, String gateResult, String gateReason, GzJpSnapPriceVO price, Integer quoteErrorCode) {
    public GzJpSnapDetailVO(String externalId, String titleJa, String titleZh, String descriptionJa, String descriptionZh,
        long priceJpy, String itemStatus, String shippingPayer, String conditionText, String categoryId, List<String> imageUrls,
        boolean purchasable, String unavailableReason, Long referencePriceCent) {
        this(externalId, titleJa, titleZh, descriptionJa, descriptionZh, priceJpy, itemStatus, shippingPayer, conditionText, categoryId, imageUrls, purchasable, unavailableReason, referencePriceCent, "blocked", unavailableReason, null, null);
    }
}
