package org.dromara.gz.jp.service.internal;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.gz.jp.domain.entity.GzJpSnapItem;
import org.dromara.gz.jp.domain.vo.GzJpSnapPriceVO;
import org.dromara.gz.jp.domain.vo.GzJpSnapQuotePreviewVO;
import org.dromara.gz.jp.mapper.GzJpSnapItemMapper;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class GzJpSnapQuotePreviewService {
    private final GzJpSnapQuoteService quotes;
    private final GzJpSnapItemMapper items;

    /** Never refresh the detail or quote: checkout preserves the original signed price and deadline. */
    public GzJpSnapQuotePreviewVO preview(String quoteId) {
        var quote = quotes.require(quoteId);
        var item = items.selectOne(new LambdaQueryWrapper<GzJpSnapItem>()
            .eq(GzJpSnapItem::getPlatform, "mercari").eq(GzJpSnapItem::getExternalId, quote.externalId()));
        if (item == null) throw new ServiceException("报价商品不存在，请重新报价", 4123);
        var price = new GzJpSnapPriceVO(quote.goodsCent(), quote.fixedFeeCent(), quote.estShippingCent(),
            quote.totalCent(), quote.priceJpy(), quoteId, quote.expiresAt());
        return new GzJpSnapQuotePreviewVO(quote.externalId(), item.getTitleZh(), item.getTitleJa(), price);
    }
}
